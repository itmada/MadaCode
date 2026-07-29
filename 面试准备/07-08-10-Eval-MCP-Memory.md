# 模块 08 · MCP 客户端（必考重点）

> 源码位置：`mcp/McpClient.java`（228 行）+ `mcp/transport/StdioTransport.java`（118 行）+ `mcp/McpConnectionManager.java`（146 行）+ `McpToolAdapter`（72 行）
> 对应简历 bullet："实现 MCP 客户端，抽象传输层支持 stdio 子进程接入外部工具生态，基于 JSON-RPC line 协议通信，外部工具动态注册为本地工具实例后复用同一套权限 / 并发 / 编排"
> 面试热度：MCP 是当下最热的 Agent 关键词，面试官大概率自己也在学，会问得起劲。你有从零实现，这是硬通货。

---

## 一、30 秒电梯陈述（背下来）

> "我没有用官方 SDK，从零实现了 MCP 客户端栈，分三层：**传输层** `StdioTransport` 用 ProcessBuilder 起子进程、走 stdin/stdout 传 JSON 行，stderr 用守护线程排空防止子进程写满缓冲区阻塞；**协议层** `McpClient` 实现 JSON-RPC 2.0——initialize 握手、tools/list 发现、tools/call 调用，异步响应用 `CompletableFuture` 按请求 id 路由，reader 守护线程逐行 dispatch；**集成层** `McpConnectionManager` 并发启动所有配置的 server，把远端工具的 schema 包装成 `McpToolAdapter` 注册进本地 `ToolRegistry`——从这一刻起，外部工具和内置工具走完全相同的权限门、并发调度、编排管线，模型甚至不知道一个工具是本地的还是远程的。"

---

## 二、三层架构图

```
McpConnectionManager（集成层：生命周期 + 工具桥接）
 └─ McpServer × N（每个配置的 server 一个实例，状态机 STARTING→READY/ERROR）
     └─ McpClient（协议层：JSON-RPC 2.0）
         └─ StdioTransport（传输层：子进程 stdin/stdout）

发现的工具 → McpToolAdapter（实现 Tool 接口）→ ToolRegistry.register()
```

**最核心的设计判断**：`McpToolAdapter` 实现的是和 `BashTool`、`FileReadTool` 完全相同的 `Tool` 接口。这意味着 MCP 工具**自动获得**：权限门检查（PermissionGate）、并发安全分类（isConcurrencySafe）、输入校验、Hook、取消传播——一行都不用重写。"接入新生态"变成了"新增一个 Tool 实现类"。

---

## 三、传输层走读（StdioTransport）

```java
ProcessBuilder pb = new ProcessBuilder(command);
pb.redirectErrorStream(false);              // stderr 独立，不混入协议流
process = pb.start();
writer = ... process.getOutputStream();     // 我们写它的 stdin
reader = ... process.getInputStream();      // 我们读它的 stdout
```

**三个面试细节：**

1. **为什么 stderr 必须用后台线程排空？**（L60-71）子进程往 stderr 写日志，如果没人读，OS 管道缓冲区写满后子进程会**阻塞在写调用上**——整个 MCP server 假死。守护线程持续排空并缓存（`drainStderr()` 可供诊断时查看）。这是所有子进程管理的经典坑，答出来就是"真踩过坑"的证据。
2. **`send` 上的 synchronized（L77）**：多个线程可能同时发请求，JSON 行写入必须原子（写半行会破坏协议帧），锁住 writer 保证一行完整写出。
3. **close 用 `destroyForcibly()`**：MCP server 是外部代码，不能指望它响应温和的终止信号，直接强杀，反正协议状态在我们这边。

## 四、协议层走读（McpClient）——异步路由是核心

```java
// 发送方：注册 future，按 id 挂起
int id = nextId.getAndIncrement();
CompletableFuture<JsonNode> future = new CompletableFuture<>();
pending.put(id, future);
transport.send(request);
return future.orTimeout(timeoutSeconds, SECONDS).get();

// reader 线程：逐行读，按 id 完成对应 future
CompletableFuture<JsonNode> future = pending.remove(id);
if (msg.has("error")) future.completeExceptionally(...);
else future.complete(msg.path("result"));
```

**为什么需要这个机制？** JSON-RPC 是异步协议——响应顺序不保证和请求顺序一致（server 可以先回慢请求后回快请求）。`pending: Map<Integer, CompletableFuture>` 把"哪个响应属于哪个请求"的路由问题解决掉：发送方阻塞在自己的 future 上，reader 线程按 id 精确唤醒。

**配套细节：**
- **两级超时**：initialize 10 秒、普通请求 30 秒（`orTimeout`）——握手不该慢，工具调用可以慢一点。
- **断连时 `failAllPending`**（L72-77）：reader 线程发现流关闭，把所有挂起的 future 全部异常完成——没有这一步，所有等待中的调用方会永远挂起。finally 里再兜一次。
- **握手序列**：`initialize` 请求 → 校验响应有 capabilities → 发 `notifications/initialized` 通知（无 id、不期待响应）。这是 MCP 规范的三步握手。
- **通知 best-effort**（L195-205）：通知发送失败静默忽略——协议语义上通知本来就不保证送达。

## 五、集成层走读（McpConnectionManager）

- **并发启动**（L67-93）：所有非 disabled 的 server 用线程池（≤8 线程）并行启动。为什么？MCP server 启动是慢操作（npm 包冷启动可能数秒），串行启动 5 个 server 用户要等半分钟。
- **单 server 失败不拖垮整体**（startOne L98-116）：try-catch 包住单个 server 的启动，失败→标记 ERROR 状态+记录错误信息+close，其他 server 照常。**部分可用优于全部不可用**。
- **状态机**：`STARTING → READY / ERROR / DISABLED`——用户可以用命令查看每个 server 的状态和错误原因。

---

## 六、面试官追问预案

**Q1：MCP 是什么？为什么不直接用 HTTP API 接工具？**
参考：MCP（Model Context Protocol）是 Anthropic 推的开放协议，统一"模型宿主↔工具提供方"的接口——工具方只要实现一次 MCP server，就能被任何 MCP 宿主（Claude Desktop、各类 Agent）使用。对比自己接 HTTP API：每个工具一套认证/schema/调用约定，N×M 的适配工作；MCP 把它变成 N+M。stdio 传输的好处是零网络配置——server 就是个本地子进程，权限继承自宿主进程。

**Q2：为什么自己实现而不用官方 SDK？**
参考：一是学习目的——协议层不复杂（JSON-RPC 2.0 + 三步握手），自己实现能真正理解协议；二是控制粒度——我需要把 MCP 工具无缝融入自己的 Tool 接口、权限门和并发调度，官方 SDK 的抽象未必匹配；三是依赖极简——整个实现 500 行左右，不值得引一个大依赖。

**Q3：MCP server 进程崩了，正在等待响应的调用会怎样？**
参考：reader 线程 `readLine()` 返回 null 或抛 IOException → `failAllPending` 把所有挂起 future 异常完成 → 等待中的工具调用立刻收到 McpException，作为 per-tool 失败结果回给模型（不是永远挂起，也不是杀死回合）。加上 30 秒请求超时兜底，不存在无限等待的路径。

**Q4：外部工具怎么受你的权限体系管控？**
参考：`McpToolAdapter` 实现 `Tool` 接口后注册进同一个 `ToolRegistry`，执行时走 `ToolExecutor` 的完整七道关卡——权限门在第七关，MCP 工具调用一样要过 PermissionGate，需要审批就弹框。声明并发安全性时 MCP 工具保守地按不安全处理（串行执行），因为我们无法知道远端工具是否有副作用。

**Q5：响应乱序怎么办？比如先发的请求后收到响应？**
参考：这正是 pending map + id 路由解决的问题。每个请求分配自增 id，响应携带同一 id；reader 线程 `pending.remove(id)` 找到对应 future 完成它。哪个先回完成哪个，请求方各自阻塞在各自的 future 上，互不干扰。

---

# 模块 07 · Eval 评测框架（中等强度）

> 源码位置：`eval/` 包（8387 行——项目里最大的包，比核心引擎还大）
> 对应简历 bullet："真实模型端到端运行评测 case，按环境故障/执行失败/验收失败三维正交判分；Docker 网络隔离执行，模型凭证不进入被测容器"
> 面试定位：主动抛出的亮点，不是被动防守点。"我给自己的 Agent 写了 8000 行评测框架"这句话本身就是差异化。

## 一、30 秒电梯陈述

> "Agent 改一行 prompt 可能悄悄破坏另一个能力，所以我建了端到端评测框架：22 个 case（14 个真实工程任务 + 3 个 long-running + 5 个框架自检），每个 case 起真实的 agent 管线跑完整任务，然后用评分器管线判分。判分是**三维正交**的：INFRA_ERROR（环境故障，不算模型的错）、执行状态（超时/崩溃/API错误）、判定结果（验收脚本+多维评分器）。因为真实模型是非确定的，每个 case 支持跑 N 个 attempt 算 pass@k。隔离用 Docker：被测容器不直接持有模型 API key——凭证放在宿主侧的 egress 代理 sidecar 里，容器只拿到代理地址，请求经代理转发时由代理注入密钥。"

## 二、必须能讲清的四个设计点

**1. 三维正交判分——为什么"环境故障"要单列？**
如果 Docker 拉镜像失败也算 FAIL，评测结果就混入了和模型能力无关的噪音，回归对比失去意义。`FinalVerdict = {PASS, FAIL, INFRA_ERROR}`，INFRA_ERROR 单独统计、不计入通过率。判分维度："这次跑起来了吗（环境）→ 跑完了吗（执行）→ 干对了吗（验收）"，三个问题独立回答。

**2. pass@k 与多 attempt（EvalRunner 注释原文）**
"a real model is non-deterministic, so one attempt is a high-variance binary"——单次通过/失败是高方差信号，同一 case 跑 N 次取 pass@k 才能区分"能力问题"和"运气问题"。

**3. 凭证托管（DockerEgressProxySession）——最亮的安全设计**
被测容器里跑的是模型生成的代码，**模型可以在容器里执行任意命令**——如果 API key 以环境变量放进容器，一条 `env` 命令就泄露了。方案：宿主起 `mada-egress-proxy` sidecar 持有真实密钥，被测容器的 baseUrl 指向 `http://mada-egress-proxy:8080/provider/N`，代理收到请求后注入真实密钥再转发。容器从头到尾摸不到密钥。egress 事件还写入 JSONL 日志供 SafetyScorer 审计出网行为。

**4. 评分器管线 fail-closed + 指纹**
`ScorerPipeline` 要求必有 VERIFY 维度（验收脚本），缺了直接报错而不是默认通过。对比两次评测时强制校验"scorer 指纹"一致——评分器版本变了的对比没有意义，直接拒绝比较。

## 三、诚实红线（再强调一次）

仓库 `eval/reports/` 里现在只有 selftest 的跑分记录（provider=none），**没有真实模型端到端跑分数据**。面试说法："框架完整可跑，17 个真实任务 case 就绪；受 API 成本限制，常态化跑的是框架自检集。"绝不编造通过率。如果面试前有预算，我们可以真跑一轮拿数字。

## 四、追问预案

**Q1：你怎么知道你的 Agent 改了代码没有变差？** → 电梯陈述 + case 级回归对比（EvalReportCompare）。
**Q2：评测里模型作弊怎么办（比如直接改验收脚本）？** → 有 antihack case 专门测这个；验收脚本由宿主侧执行，容器内不可写；SafetyScorer 审计危险行为。
**Q3：为什么用 Docker 而不是直接本地跑？** → 被测的是模型生成的任意代码，必须假设它可能 rm -rf、扫描文件系统、外发数据。网络隔离 + 文件系统隔离 + 凭证托管三件套。本地模式（LOCAL_UNSAFE）保留用于框架自检，报告里显式标注信任边界。
**Q4：pass@k 的 k 怎么选？** → 成本和方差的权衡。k=1 高方差，k=5 成本五倍。默认 1，重要回归用 3-5。

---

# 模块 10 · Memory 子系统（轻量）

> 源码位置：`memory/` 包（约 350 行，半小时读完）
> 对应简历 bullet："设计分类型 Memory 子系统，将 Agent 长期记忆建模为 user/feedback/project/reference 四类，frontmatter 文件持久化 + 索引层在启动时按需注入上下文"

## 一、两分钟版本（面试够用）

> "Agent 的上下文是会话级的，跨会话就失忆了。我的 Memory 子系统把长期记忆建模为四类：**user**（用户是谁、偏好什么）、**feedback**（用户纠正过我什么）、**project**（项目正在发生什么）、**reference**（信息在外部哪里找）。每条记忆是一个独立 Markdown 文件，用 frontmatter（name/description/type 三个字段）做元数据，纯文本可读可编辑。`MemoryStore` 维护一个 MEMORY.md 索引文件——每条记忆在索引里占一行（名字+链接+一句话描述）。启动时 `MemoryLoader` 只把**索引**注入系统提示词，不是全部记忆内容——模型看到索引后，需要哪条再用文件读取工具去取。这是两级加载：索引常驻（便宜），正文按需（不挤占上下文）。写入走 `memory_save` 工具，upsertIndex 保证索引和文件同步。"

## 二、三个可能的追问

**Q1：为什么分四类，不用一个大文件？**
参考：四类的**使用时机**不同——user/feedback 几乎每次对话都相关（该怎么跟这个人协作），project 只在做相关任务时相关，reference 只在找信息时相关。分类让模型能按需检索；单个大文件会随时间膨胀成"必须全文加载"的上下文炸弹。

**Q2：为什么用 Markdown+frontmatter 而不是数据库/向量库？**
参考：规模不需要——个人 Agent 的记忆是几十条量级，文件系统就是最好的数据库；可读可编辑——用户能直接打开文件改自己的记忆，透明可控；frontmatter 是自己解析的（60 行,没引 YAML 库），因为只需要三个固定字段。向量检索适合"万条级+语义模糊查询"，这里是"十条级+模型自己按索引选"，杀鸡不用牛刀。

**Q3：记忆错了/过时了怎么办？**
参考：memory_save 工具支持覆盖写（upsertIndex 按文件名替换索引行），模型在发现记忆与现实冲突时更新它。文件系统的另一个好处：用户永远有最终否决权，直接删文件就行。

---

## 三份文档的自查清单

- [ ] 08：我能画出 MCP 三层架构、讲清 pending map 路由机制、stderr 排空的原因
- [ ] 08：我能回答"MCP 工具怎么受权限管控"（同一个 Tool 接口 → 七道关卡）
- [ ] 07：我能讲三维正交判分、pass@k 的动机、凭证托管方案
- [ ] 07：我记得评测数字的诚实红线
- [ ] 10：我能两分钟讲完四类记忆+两级加载
