# MadaCode 架构设计

> 本文描述 MadaCode 的核心运行机制与关键设计决策。约 4.2 万行 Java（不含测试），零运行时框架依赖（无 Spring），Java 21。

## 模块总览

**控制流**——一次用户输入的执行链：

```text
REPL（cli）读取输入 / ESC 取消
  └→ QueryEngine 主循环（core/engine），每轮：
       1. services/compact   压缩检查（85% 阈值 · 渐进策略链）
       2. services/api       流式调用模型（错误分类 · 退避重试）
       3. 模型返回 tool_use 时：
          ToolOrchestrator   读写切段 · Virtual Threads 并行
            └→ ToolExecutor  输入校验 → permission 规则链 → hook → 执行
                 └→ Tool     26 个内置工具，其中：
                      · AgentTool — 派生子 Agent，递归复用 QueryEngine
                      · MCP 适配工具 — stdio 外部进程动态注册
```

**数据与事件流**——所有消息和事件汇入 `ConversationSession`（core/session），再分两路：

```text
ConversationSession（双视图会话状态）
  ├→ 完整 transcript（只追加） → SessionEventBus / render / TUI / JSONL / --resume
  └→ model context（可压缩） → token 估算 / compact / 下一次模型请求
```

设计基调：**可见历史与模型上下文分离**。工具结果、用户取消、压缩提示、错误终态都会进入只追加的完整 transcript，供 UI 渲染、持久化和断点恢复使用；compact 仅替换模型上下文，因而不会删除用户可审计的原始历史。

---

## 1. Agent 主循环：异构事件收敛为消息流终态

`QueryEngine.runTurn`（[QueryEngine.java](../src/main/java/madacode/core/engine/QueryEngine.java)）是整个系统的心脏。用户输入追加为 user 消息后，每轮迭代依次执行：

1. **取消检查** — 用户已按 ESC 则直接走取消终态；
2. **压缩检查** — token 估算超过 85% 阈值则执行压缩（压缩本身耗时数秒、可被取消，取消后不再发起注定失败的模型请求）；
3. **重建系统提示词与可见工具集** — 每轮重算，轮内状态变化（如长任务阶段切换）即时反映到下一次模型请求；
4. **流式调用模型** — `AssistantTurnWriter` 边接收边写入，正常完成 commit 进消息流，异常或取消则 abandon，半截消息不污染会话；
5. **无工具调用** → 终态 `COMPLETED`（或输出触顶时 `MODEL_TRUNCATED`），本轮结束；
6. **有工具调用** → 交给 ToolOrchestrator 切段执行，结果按模型原始顺序合并为一条 user 消息，回到第 1 步。

**终态收敛**：无论哪条路径退出，都会向消息流写入一条终态 assistant 消息：

| 终态 | 触发条件 |
|---|---|
| `COMPLETED` | 模型给出最终回答 |
| `MODEL_TRUNCATED` | 模型输出触达 max_tokens |
| `CANCELLED` | 用户 ESC（含压缩期间、流式期间） |
| `PERMISSION_CANCELLED` | 用户在权限弹窗中拒绝并终止 |
| `API_ERROR` | 重试耗尽后的 API 失败 |
| `MAX_ITERATIONS` | 迭代上限保护 |

**协议完整性不变量**：模型发出的每个 `tool_use` id 必须有配对的 `tool_result`（Anthropic/OpenAI 协议均要求，否则 400）。任意时刻取消，编排器都会为未完成的调用补齐结果（见下节），保证会话可以无缝续聊。

流式输出通过 `AssistantTurnWriter` 的 commit/abandon 两段式写入：正常完成 commit 进消息流，异常或取消则 abandon，避免半截消息污染会话。

## 2. 工具系统：声明式定义 + 读写切段并发编排

### 声明式工具协议

每个工具用 Java Record 定义输入结构，编译期即获得类型校验；运行期由 `ToolInputCoercion` 将模型的 JSON 输入强制转换为类型化对象，失败则直接以错误 `tool_result` 回告模型自行修正。26 个内置工具覆盖文件读写、搜索、Bash、Web、计划、子 Agent、MCP 资源等。

### 切段并发（[ToolOrchestrator.java](../src/main/java/madacode/core/engine/ToolOrchestrator.java)）

模型同轮发出多个工具调用时，按**并发安全性**切分为连续段：

```text
模型调用顺序:  [Read A] [Grep B] [Read C] [Edit D] [Read E]
切段结果:      |—— 并发段(VT 并行) ——|  |串行|  |串行|
回填顺序:      始终按模型原始顺序 A B C D E
```

- 并发安全性**不是静态标记**，而是逐调用动态判定：`tool.isConcurrencySafe(typedInput)` 拿到类型化输入后裁决——同一个 Bash 工具，`ls` 与 `rm` 可以得到不同答案。
- 并发段使用 `Executors.newVirtualThreadPerTaskExecutor()`（每调用一个虚拟线程）+ `ExecutorCompletionService` 收割，结果按 slot 索引写回原位。
- 取消联动：并发段开始前在取消令牌上注册 kill-hook（`onCancel → shutdownNow`），段结束即通过 try-with-resources 撤销订阅——避免回调指向已关闭的执行器并在令牌上滞留。

### 取消语义（ESC 任意时刻按下）

| 工具状态 | 回填的 tool_result |
|---|---|
| 已执行完成 | 真实结果 |
| 执行中 | `Cancelled: <reason>`（阻塞 IO 在 read 返回后感知中断） |
| 尚未开始 | `Cancelled before execution: <reason>` |

三种情况合并后仍是一组完整配对的 `tool_result`，协议不变量不破坏。

## 3. 上下文压缩：单阈值 + 渐进式策略链

[services/compact](../src/main/java/madacode/services/compact/)：每轮模型调用前，`TokenEstimator` 估算 model context token，超过 **85% 软阈值**（`CompactBudget`）则按序尝试策略链：

```text
token 估算 > 85%
   └─→ Micro 策略：裁剪历史工具结果，保留最近 3 轮   ──达标→ 继续本轮
          └─（仍超限）→ Full 策略：Map-Reduce 全量摘要 ──→ 继续本轮
```

- 压缩本身可能调用模型（耗时数秒），全程响应取消令牌，可安全中止。
- 每次压缩在完整 transcript 中留下审计记录：`[compact] 120k → 40k via micro (87 summarized, 12 kept)`；摘要边界只留在 model context，不会取代 TUI 的历史。
- 也可通过 `/compact` 命令强制触发（`forceCompact`）。

## 4. Sub-Agent：递归调度与父子事件边界

子 Agent（[agent/](../src/main/java/madacode/agent/)）拥有独立的上下文、工具池与权限边界，但**复用同一个 QueryEngine 主循环**——递归即组合。

父子之间通过 `ParentEventForwarder` 做**选择性事件转发**：

| 事件 | 是否冒泡 | 原因 |
|---|---|---|
| Token 用量报告 | ✅ | 父级计费总额必须包含子 Agent 消耗 |
| 工具活动（脱敏摘要） | ✅ | 父级 UI 展示"子 Agent 正在读取/搜索…"轻量进度 |
| 孙级生命周期摘要 | ✅ 逐层 | 深层嵌套时每层 forwarder 向上传一层 |
| 子 Agent 消息流 | ❌ | 并入父上下文会造成污染与 token 爆炸 |
| 子 Agent 的 Plan 事件 | ❌ | 子计划属于子 Agent，不是父级的计划 |

子 Agent 的最终产出以单条 `tool_result` 返回父级；Plan Mode 状态由父向子继承。

## 5. 权限层：可插拔规则链

[permission/](../src/main/java/madacode/permission/)：工具执行前经过 `PermissionGate`，内部是规则链——每条 `PermissionRule.evaluate(...)` 返回 `Optional<PermissionDecision>`，空表示弃权交给下一条。内置规则包括只读直放、Bash 安全分析、文件系统作用域、计划/长任务状态约束、bypass 模式等；裁决不了的弹出交互式审批，结果写入审计日志。用户拒绝同样以 `tool_result` 回告模型（协议不变量不破坏）。

## 6. MCP 客户端

[mcp/](../src/main/java/madacode/mcp/)：抽象传输层，目前实现 stdio 子进程通道，按 JSON-RPC line 协议通信。外部 MCP 工具经 `McpToolAdapter` 动态注册为本地工具实例——**复用同一套权限 / 并发切段 / 编排管线**，对主循环完全透明。文本资源直接返回，二进制资源落盘 `~/.mada/blobs` 后以路径返回。

## 7. 弹性 API 层

[services/api](../src/main/java/madacode/services/api/)：
- `ApiErrorClassifier` 按 HTTP 状态与响应体分类错误（可重试 / 不可重试 / 限流）；
- `RetryingApiClient` 装饰器实现指数退避 + 抖动重试，**睡眠器与抖动函数以 `LongUnaryOperator` 注入**——重试逻辑可在测试中以虚拟时间确定性验证；
- 流式 SSE 解析经 `ApiStreamSink` 增量回调，支撑边生成边渲染。

## 8. Eval：类型化、可追溯的能力评测

[eval/](../src/main/java/madacode/eval/) 使用真实模型和真实工作流执行能力型 case。每个 case
在独立 execution environment 中运行，随后由 `verify.sh` 在 workspace 快照中判分；Judge
是否对 Agent 隐藏由 environment 的信任边界声明。

结果被拆成三个正交维度：

- `HarnessStatus`：评测基础设施是否正常；
- `ExecutionStatus`：Agent/工作流如何结束；
- `JudgeStatus`：产物是否通过客观验收。

只有三者满足 `OK + COMPLETED + PASS` 才产生最终 PASS，因此执行崩溃不会因为初始 workspace
碰巧正确而形成假阳性。`RunBudget` 统一限制 turn、worker、墙钟时间和 Judge 进程；报告附带
代码、case、provider/model、扩展配置与隔离级别的 manifest。

当前默认执行环境为显式标记的 `LOCAL_UNSAFE` 临时工作目录后端，真实模型运行需主动确认。
manifest 除隔离级别外还记录 Judge 可见性、宿主机访问、网络访问与 `trustedMeasurement`：
`LOCAL_UNSAFE` 是本地 smoke/cost/stability 测量，不能声称为隐藏 Judge benchmark。完整可信
评测需要容器或 VM 后端，让整个 Agent attempt runtime 只看到 workspace，并把 Judge bundle
仅挂载到判分阶段。`EvalExecutionEnvironment` 只覆盖 workspace/verify 边界；ADR 0001 中定义的
`AttemptExecutor` 接缝已经落地，docker 后端通过容器内 `EvalAttemptMain` 运行 attempt，并用
显式 wire DTO 与 host 交换 attempt input/outcome，不直接跨容器边界序列化内部 domain record。
Docker attempt/verify 命令共用 shell-entrypoint builder，避免镜像 ENTRYPOINT 与运行脚本语义分叉。
真实 runtime docker 后端会创建 Docker internal network 和 allowlist proxy sidecar：agent 容器只接入
internal network，provider baseUrl 被改写到 `mada-egress-proxy`，真实 provider token 只注入 proxy，
proxy 负责转发 provider API 并记录 `EgressReport`。host 侧统一清洗 provider token 后再写报告和产物。
no-model docker self-test 不启动 provider proxy，manifest 仍保持 unobserved/trusted=false。

## 9. 装配与资源管理

[bootstrap/](../src/main/java/madacode/bootstrap/)：不引入 DI 框架，模块依赖图固化为 13 个显式 Assembly/Module 的装配顺序。`BootstrapResources` 以 LIFO 栈管理所有 `Closeable`：启动失败按逆序全量释放；启动成功则整体移交 REPL 并接管 JVM shutdown hook；`ManagedCloseable` 用 CAS 保证任意路径下的幂等关闭。

## 10. Long-Running 模式

[longrunning/](../src/main/java/madacode/longrunning/)（30 个类）：面向大规模重构等超长任务。任务以文件形态持久化，Worker Agent 以多轮独立上下文循环领取执行；任务租约（lease）防止多实例争抢，workspace checkpoint 支持崩溃后断点恢复，worker 与监控会话通过事件日志桥接实现实时进度观测。

---

## 关键设计决策（FAQ）

**为什么不用 Spring？** CLI 应用要求启动速度与单 jar 分发；依赖图在编译期完全已知，显式装配比反射扫描更可控、可调试。代价是手写装配顺序——用 Assembly 分层缓解。

**为什么并发切段而不是全并行？** 写操作之间、读写之间存在顺序依赖（模型按其推理顺序发出调用）。按并发安全性切段是"保守但正确"的折中：只读段获得并行收益，写入段保持模型预期的顺序语义。

**为什么压缩只有单软阈值？** 85% 触发点为模型留出完整一轮的输出余量；Micro→Full 两层策略已覆盖绝大多数场景。硬阈值（强制截断保底）在规划中。

**为什么子 Agent 消息不并入父上下文？** 子 Agent 存在的意义就是上下文隔离——父级只需要结论，不需要过程。token 计费除外（钱必须算总账）。
