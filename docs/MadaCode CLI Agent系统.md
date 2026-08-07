# MadaCode CLI Agent系统

MadaCode 是一款用 Java 21 实现的 CLI 端 LLM Agent 编程工具，覆盖 agentic loop、工具协议、权限层、子 agent 派生、会话压缩、MCP 接入和 Long-Running 任务等核心机制。

> 更新说明（2026-08）：本文按当前仓库实现校准。项目入口为 `madacode.MadaAgentCLI`，主代码约 500 个 Java 文件，构建产物为 `target/MadaCode.jar`。当前实现还包含独立的 Eval 运行框架，支持本地、Docker 和 Git worktree 执行环境。

## 当前实现速览

| 关注点 | 当前实现 | 主要代码位置 |
| --- | --- | --- |
| CLI 启动与装配 | 参数解析后由 `Bootstrapper` 组装 REPL、Provider、工具、权限和资源 | `madacode.MadaAgentCLI`、`madacode.bootstrap` |
| Agent 主循环 | `QueryEngine` 负责压缩、流式模型请求、工具调用、结果回填和终止判定 | `madacode.core.engine` |
| 会话状态 | 不可变历史快照 + 单写线程约束；读端可跨线程读取 | `madacode.core.session.ConversationSession` |
| 工具协议 | 泛型 `Tool<I>`，由 Jackson 将 JSON 输入转换为类型化输入，并生成 schema | `madacode.tool.Tool`、`tool.validation`、`tool.schema` |
| 工具并发 | 按 `isConcurrencySafe(input)` 连续切段；安全段使用 Virtual Threads，其余串行；`mustRunAlone` 工具独占一轮 | `madacode.core.engine.ToolOrchestrator` |
| 权限与工具可见性 | `PermissionGate` 与 `ToolAccessResolver` 分别负责执行审批和按工作流筛选工具 | `madacode.permission`、`madacode.tool.access` |
| 长任务 | Worker、任务状态机、租约、监控、恢复和 workspace checkpoint | `madacode.longrunning` |
| 能力评测 | 类型化 case、运行清单、checkpoint、JSON/HTML 报告；支持 Docker/worktree | `madacode.eval` |

当前 `QueryEngine` 的终止原因是 `COMPLETED`、`MODEL_TRUNCATED`、`MAX_ITERATIONS`、`API_ERROR`、`CANCELLED` 和 `PERMISSION_CANCELLED`；工具调用次数不会单独产生终态。

## 执行全链路

**启动阶段：**

 ```
     ①                 ②                       ③
    main()  ──→  Bootstrapper DI 容器    ───→ REPL 接管
                    (装配 + 加载)         (移交 closeables)
                        │
                        ├─ 加载  Memory (AGENTS.md + ~/.mada/memory/MEMORY.md 索引 → system prompt)
                        ├─ 加载  Skill / Agent definitions  
                        ├─ 建立  MCP 连接 (stdio 子进程握手) 
                        └─ 注册  ToolRegistry (内置工具 + MCP 适配工具)
 ```

**执行阶段（用户输入主循环）：**

  ```
    ④ user prompt
        │
        ▼
    ⑤ REPL 分发
        · slash 命令: 走 LocalTurnTask (跳过模型循环)
        · 普通输入:   走 model turn (继续 ⑥)
        │
        ▼
    ⑥ TurnExecutor.submit(session, input)
        · 单线程调度
        · log Started
        │
        ▼ (worker 线程)
    ⑦ QueryEngine.runTurn
        · session.addMessage(user)
        │
        ▼
    ┌─ Inner Loop ─
    │
    │  ⑧  Compact   (超 soft 才触发; Micro 失败回落 Full)
    │
    │  ⑨  apiClient.send  (流式)
    │      · text 边到边渲
    │      · tool_use 收集成 toolCalls
    │
    │  ⑩  终态判定
    │      ├─ 无 toolCalls + max_tokens:   退出 (MODEL_TRUNCATED)
    │      ├─ 无 toolCalls + 其他:          退出 (COMPLETED)
    │      └─ 有 toolCalls:                继续 ⑪
    │
    │  ⑪  ToolOrchestrator.run
    │      · 按 isConcurrencySafe 切段
    │          concurrency-safe 段: Virtual Threads 并行
    │          其他段: 串行
    │      · 每个 call: PermissionGate, 然后 Tool.execute
    │
    │  ⑫  tool_result 按原序回填进 session
    │      │
    │      └──→ 回到 ⑧
    │
    └─ 循环耗尽: 退出 (MAX_ITERATIONS)

        │ TurnResult (含 FinishReason)
        ▼
    ⑬ TerminalState.fromResult
        唯一翻译点: FinishReason → TerminalState
        │
        ▼
    ⑭ log Finished + 释放 session 锁
        │
        └──→ 回到 ④
  ```

**结束阶段：**

  ```
    (通过 exit / Ctrl-D 退出)
        │
        ▼
    ⑯ REPL.run() 返回
        │
        ▼
    ⑰ REPL.closeResources()
        · 遍历 shutdownTargets, 逐个 close
        · 这些 Closeables 是 Bootstrap 阶段 transferTo 移交过来的
        │
        ▼
    ⑱ System.exit(0)
        · JLine/jansi 留下 non-daemon 线程会阻止 JVM 自然退出
        · 显式 exit 才能让进程真的结束
        │
        ▼
     进程退出

     ┄┄┄ 兜底路径 (kill 信号 / 未捕获异常 / 进程被宿主拉走) ┄┄┄
     JVM shutdown hook 触发 (Bootstrap 启动期注册)
         → BootstrapResources.close() 把还活着的 Closeables 全关掉
     · ManagedCloseable 幂等: 已被 ⑰ 关过的, 这里安全跳过
     · 这条路径不依赖 REPL 正常退出, 所以崩溃也能保证资源释放
  ```

## 会话与消息模型

### **会话容器层**  

ConversationSession 模型是一次对话的全局状态容器，是整个 agent 运行时的单一状态权威（Single Source of Truth）。所有组件——QueryEngine、工具、渲染器——都通过它读写对话状态，而不是各自维护副本。

| 字段               | 类型                                    | 作用                            |
| ------------------ | --------------------------------------- | ------------------------------- |
| `sessionId`        | `String`                                | 唯一标识，持久化用              |
| `workingDirectory` | `Path`                                  | 工具操作的基准目录              |
| `historyRef`       | `AtomicReference<HistoryState>`         | transcript 与可压缩 model context 快照 |
| `tokenUsageRef`    | `AtomicReference<TokenUsage>`           | 累计 token 消耗                 |
| `inputHistoryRef`  | `AtomicReference<List<String>>`         | 用户输入历史（命令行补全用）    |
| `currentPlanRef`   | `AtomicReference<CurrentPlan>`          | 当前计划快照                    |
| `planMode`         | `volatile boolean`                      | 是否处于 Plan Mode              |
| `listeners`        | `CopyOnWriteArrayList<SessionListener>` | 事件订阅者（渲染器等）          |
| `currentStream`    | `StreamingAssistantHandle`              | 流式响应期间非 null，作为互斥锁 |

ConversationSession 的主要作用：

- 对话历史管理

addMessage() 追加消息，内部强制校验相邻消息角色不同（Anthropic API 协议要求）。用 AtomicReference 持有不可变 List 快照，写时 copy-on-write，读端任意线程安全。

- 流式响应协调

beginAssistantStream() 开启流式写入，返回 StreamingAssistantHandle。期间 currentStream != null，调用 addMessage() 会抛异常——防止流未结束就往 session塞消息破坏消息序列。

- 事件总线

工具执行过程中通过 fireToolExecutionStarted/Progress/Completed 广播事件，TurnRenderer 作为 SessionListener 订阅这些事件，驱动终端界面实时刷新。每个 fire 方法内部吞掉 listener 异常，防止渲染崩溃影响执行主链路。

- Plan 状态管理

通过 `CurrentPlan` 保存当前计划快照，并由 `UpdatePlanTool` 完成计划更新。

- 持久化载体

SessionStorage 把整个 ConversationSession 序列化到磁盘（消息历史 + plan + token 统计），/resume 时反序列化恢复。

### **消息模型层**

1. Message 模型

Message 是整个系统中最基础的数据单元——Anthropic Messages API 的请求和响应都围绕它，session 的消息历史是 List<Message>，持久化的 JSON 文件核心内容也是 messages 数组。

 一句话定义：一条 Message = 一个角色（role）+ 一组内容块（ContentBlock 列表）。

```
  public final class Message {
      private final MessageRole role;              // SYSTEM / USER / ASSISTANT
      private final List<ContentBlock> contentBlocks;  // 不可变 List
  }
```

| 用途                   |   role    |                      contentBlocks 内容                      |                  由谁生成                  |
| :--------------------- | :-------: | :----------------------------------------------------------: | :----------------------------------------: |
| 用户   输入            |   USER    |                    `[TextBlock("你好")]`                     |          QueryEngine.runTurn 入口          |
| 模型回复（纯文本）     | ASSISTANT |                   `[TextBlock("好的...")]`                   | StreamingAssistantHandle.finalizeAndAppend |
| 模型回复（带工具调用） | ASSISTANT | `[TextBlock("让我查一下"), ThinkingBlock("..."), ToolUseBlock("id1","Bash",{...})]` |                    同上                    |
| 工具结果回灌           |   USER    |      `[ToolResultBlock("id1","文件内容...",true,350)]`       |        QueryEngine 在执行工具后拼装        |
| 系统标记               |  SYSTEM   |            `[TextBlock("Session initialized.")]`             |   session 初始化、compact、plan mode 等    |

2. MessageRole 模型

MessageRole 是一个枚举类型（`enum`），定义了对话历史中每个消息块的**发送方角色**。它包含三个角色：`SYSTEM`（系统）、`USER`（用户）和 `ASSISTANT`（助手/模型）。

简单一句话就是： MessageRole 就是一个 message role 的枚举值（USER / ASSISTANT / SYSTEM），用于在 message 进入 session 时标记 message 的归属身份。

3. ContentBlock 模型

 ContentBlock 是消息（Message）的结构化内容单元，用 Java 的 sealed interface 定义了四种不可变的 record 变体：

```
 ContentBlock (sealed interface)
  ├── TextBlock        — 纯文本内容
  ├── ThinkingBlock    — 模型的 extended-thinking 推理内容
  ├── ToolUseBlock     — 模型发出的工具调用 (携带 id/name/input)
  └── ToolResultBlock  — 工具执行结果 (关联 toolUseId)
```

构建消息时 — Message 持有一个 List<ContentBlock>：

```
  // 纯文本消息（快捷构造，内部包成 TextBlock）
  Message.user("hello");
  Message.assistant("world");

  // 多 block 消息（工具调用场景）
  Message.assistant(List.of(
      new ContentBlock.TextBlock("Let me search..."),
      new ContentBlock.ToolUseBlock("toolu_001", "grep", inputJson)
  ));
```

### **工具交互层**  

1. ToolCall 模型

  ToolCall 是模型决定调用某个工具时产生的结构化指令，只包含三个字段：

```
  // src/main/java/madacode/core/model/ToolCall.java
  public final class ToolCall {
      String id;         // 模型分配的唯一标识 (如 "toolu_001")
      String toolName;   // 工具名 (如 "bash", "file_read", "grep")
      ObjectNode input;  // 工具输入参数 (JSON)
  }
```

简单来说，ToolCall 是 Anthropic Messages API 响应中 tool_use content block 的反序列化 DTO（Data Transfer Object）。

2. ToolResult 模型

ToolResult 是工具执行结果的 VO（Value Object），封装了工具执行完成后的三个核心字段：toolName、success、output。它是纯数据容器，没有行为，由 tool.execute() 返回，最终被包装成 ContentBlock.ToolResultBlock 回传给 API。

3.  ToolUseContext 模型

ToolUseContext 是工具运行的执行环境 — 工具运行所需的运行时依赖，整个 turn 内所有工具共享同一个实例。

| 字段                | 作用                                                    |
| ------------------- | ------------------------------------------------------- |
| `workingDirectory`  | 工具的工作目录（Bash、文件操作的基准路径）              |
| `session`           | 当前对话 session（工具可向 session 追加消息、触发事件） |
| `cancellationToken` | 取消信号，工具内部轮询 `isCancelled()` 实现协作式中断   |
| `userPrompts`       | 用户交互通道（`ask_user_question` 工具用）              |
| `depth / maxDepth`  | Agent 嵌套深度，防止 sub-agent 无限递归                 |

### **轮次生命周期层**

1. Turn 模型

Turn 是一次用户交互的生命周期元数据 DTO，它不包含对话内容（那是 Message 的职责），只记录这次交互的执行状态和时间线：

| 字段         | 作用                                                 |
| ------------ | ---------------------------------------------------- |
| `id`         | 唯一标识（`turn_xxxx`）                              |
| `sessionId`  | 归属哪个 session                                     |
| `status`     | 生命周期状态，`PENDING` / `RUNNING` → 完成/失败/取消 |
| `userInput`  | 用户输入的原始文本                                   |
| `tokenUsage` | 本次 turn 消耗的 token                               |

主要用于两个场景：

- 持久化到 TurnLog — 每次 turn 开始/结束都写盘，进程崩溃后启动时扫描未完成的 turn 并标记为失败（recoverOnStartup）。

- 取消追踪 — TurnExecutor 用 turn.id() 作为 key 管理 CancellationToken 和工作线程，Ctrl-C 时通过 id 找到对应 token 触发取消。

2. TurnResult 模型

TurnResult 是 QueryEngine.runTurn() 的返回值 VO，描述一次 turn 的执行结果摘要。

| 字段           | 作用                                 |
| -------------- | ------------------------------------ |
| `finalText`    | 模型最后输出的文本                   |
| `finishReason` | turn 的终止原因（见 `FinishReason`） |
| `iterations`   | 本次 turn 经历了几轮 API 调用        |

与 Turn 的区别：Turn 是执行过程的元数据（时间、状态），持久化到 TurnLog；TurnResult 是执行完成后的结果摘要，只在内存中流转，由 TurnExecutor 拿到后记录终态。

### **会话持久化**

项目有**两套独立的持久化机制**，各司其职：

1. **SessionStorage — 对话全量快照**

**职责**：把整个 `ConversationSession` 序列化为单个 JSON 文件，用于 `/resume`、`/continue`、`--list` 等功能。

**存储位置**：`~/.mada/sessions/<sessionId>.json`

**序列化内容**：

| 字段          | JSON key                                       | 说明                                                         |
| ------------- | ---------------------------------------------- | ------------------------------------------------------------ |
| schema 版本号 | `schemaVersion`                                | 当前为 5，加载时做向后兼容                                   |
| 会话元数据    | `sessionId` / `createdAt` / `workingDirectory` | 基本信息                                                     |
| 全部消息      | `messages[]`                                   | 每条含 `role` + `contentBlocks[]`（text / thinking / tool_use / tool_result） |
| Plan items    | `tasks[]`                                      | 每项含 id / title / status / blockedBy / activeForm          |
| Todo items    | `todos[]`                                      | content + status                                             |
| 用户输入历史  | `history[]`                                    | 命令行补全用                                                 |
| Plan Mode     | `planMode`                                     | boolean                                                      |

**调用时机**：

  - 每次 turn 结束后 `Repl.persistSession()` 自动保存
  - 退出时 `Repl.run()` 最后再保存一次
  - 切换 session 时保存旧 session

**写入策略**：先写 `.tmp` 临时文件，再通过 `ATOMIC_MOVE`原子地替换目标文件。如果文件系统不支持原子移动，降级为普通覆盖。这确保了进程崩溃时不会留下一个写了一半的损坏文件。

**读取兼容性**：通过 SchemaMigrator migration chain 实现。加载时先将旧版 JSON 逐步升级到最新 schema（v1 → v2 → ... → v5），反序列化代码只处理最新格式。每个版本步骤是独立的纯函数，新增字段只需添加一个 migration step，不需要修改反序列化逻辑。版本号大于当前值则拒绝加载。

2. **TurnLog — Turn 事件的 append-only 日志**

**职责**：记录每次 turn 的开始/结束事件，用于**崩溃恢复**——如果进程意外退出，下次启动时能识别出未完成的 turn。

**存储位置**：`<baseDir>/<sessionId>.turns/<turnId>.jsonl`

**格式**：每行一条 JSON 事件（JSONL），只有两种：

| 事件                 | 内容                                    |
| -------------------- | --------------------------------------- |
| `TurnEvent.Started`  | turnId + 时间 + 用户输入                |
| `TurnEvent.Finished` | turnId + 时间 + 终态（`TerminalState`） |

**崩溃恢复流程**（`TurnExecutor.recoverOnStartup()`）：

  1. 扫描所有 `*.turns/turn_*.jsonl` 文件
  2. 找出只有 `Started` 没有 `Finished` 的 turn
  3. 补写一条 `Finished(FAILED, "process restarted")` 事件

**两者对比：**

|          | `SessionStorage`                 | `TurnLog`                                         |
| -------- | -------------------------------- | ------------------------------------------------- |
| 存什么   | 对话全量状态（消息、plan、历史） | Turn 生命周期事件                                 |
| 格式     | 单个 JSON 文件，原子替换         | JSONL，append-only                                |
| 用途     | 恢复会话（/resume）              | 崩溃检测与恢复                                    |
| 写入时机 | turn 结束、退出、切换 session    | turn 开始和结束各写一条                           |
| 如果丢了 | 丢失对话历史，无法续用           | 丢了不影响正常运行，只是崩溃后无法识别未完成 turn |

## 主循环机制 QueryEngine

### **主循环流程**

每轮是一次“调用 API → 执行工具 → 把结果喂回去”的过程；`QueryEngine` 默认不限轮数，评测和子 Agent 可通过 `maxIterations` 显式设置上限：

  ```
整体架构
  QueryEngine.runTurn()
    ├── 拼系统提示词
    ├── 创建 ToolExecutor + ToolOrchestrator
    │
    └── for iteration 0..maxIterations:
          ├── 检查取消 → 提前退出
          ├── CompactPlanner → 压缩超长上下文
          ├── apiClient.send() → 调模型，AssistTurnWriter 流式写入 session
          │     ├── 成功 → writer.commit()
          │     └── 失败/取消 → writer.abandon() + 返回错误结果
          ├── 无 tool_use？→ 回答完成，返回 COMPLETED
          ├── toolOrchestrator.run() → 执行工具
          ├── 工具结果包装为 ContentBlock 回灌到 session
          └── 检查取消 → 退出
  ```

**终止条件**

| `FinishReason`                       | 触发条件                       | 位置     |
| ------------------------------------ | ------------------------------ | -------- |
| `COMPLETED`                          | 模型回复无 tool call，正常结束 | ④        |
| `MODEL_TRUNCATED`                    | 模型因 `MAX_TOKENS` 截断       | ④        |
| `MAX_ITERATIONS`                     | 达到配置的 `maxIterations` 上限 | 循环结束 |
| `API_ERROR`                          | API 请求失败                   | ③ catch  |
| `CANCELLED` / `PERMISSION_CANCELLED` | 用户 Ctrl-C 或权限拒绝         | ①②⑧      |

### **Agentic Loop**

LLM Agent 理论的核心模式是 ReAct (Reasoning + Acting)：感知(上下文) → 推理(模型思考) → 行动(工具调用) → 观察(工具结果) → 再推理 → ... 

MadaCode 把这个循环工程化为三个正交的组件：

| 理论概念  | 工程组件                                | 职责                                 |
| --------- | --------------------------------------- | ------------------------------------ |
| 感知+推理 | `QueryEngine` + `ApiClient`             | 管理消息历史，调模型，获得下一步意图 |
| 行动      | `ToolOrchestrator` + `ToolExecutor`     | 决定并行/串行，权限校验，执行工具    |
| 观察      | `ContentBlock` → `session.addMessage()` | 工具结果格式化后回灌对话历史         |

这三个组件是正交解耦的——`QueryEngine` 不知道工具有哪些、怎么执行；ToolOrchestrator 不知道模型怎么调用；`ToolExecutor` 不知道自己在第几轮迭代。每一层只处理自己关心的问题。

工程上的关键洞察：Agentic Loop 不是一个 while(true)，而是一个有明确终止条件的有限状态机。终止条件有 7 种（FinishReason），每种都需要不同的上层处理——COMPLETED正常返回、MODEL_TRUNCATED 提示续写、CANCELLED 丢弃结果、MAX_ITERATIONS 兜底保护。

### 系统提示词

LLM Agent 的行为边界主要由 system prompt 定义。MadaCode 通过 `SystemPromptBuilder.build()` 动态拼接：

```
  [角色定义 + 行为约束]
  [可用工具列表（内置工具 + MCP 工具） + 使用规范]
  [当前工作目录 + 环境信息]
  [项目规则与用户记忆 (AGENTS.md, ~/.mada/memory/MEMORY.md 索引)]
  [当前 Plan/Todo 状态]
  [技能列表 (Skills)]
  [Agent 定义 (如果是子 Agent)]
```

## 上下文压缩

### 触发条件

MadaCode 的上下文压缩系统负责在会话消息过多时，将历史消息压缩以控制 token 消耗，防止超出 API 上下文窗口。

```
QueryEngine.runTurn()
    │
    ├─ 每次 LLM 调用前: compactPlanner.planAndApply(session, ...)
    │    ├─ TokenEstimator.estimate() → 估算当前 token 数
    │    ├─ CompactBudget.isOverSoft() → 判断是否超软限制
    │    └─ 依次尝试策略:
    │         ├─ [1] MicroCompactStrategy  (纯 CPU，截断长工具结果)
    │         └─ [2] FullCompactStrategy   (调 LLM 生成摘要)
    │
    └─ /compact 命令: compactPlanner.forceCompact() → 无条件运行所有策略
```

**核心组件关系：**

| 组件                 | 职责                                                    |
| -------------------- | ------------------------------------------------------- |
| CompactPlanner       | 编排器：判断是否需压缩，按序执行策略                    |
| CompactBudget        | 配置：总 token=200k，软限制=85%=170k，保留最近 3 轮对话 |
| TokenEstimator       | 估算器：UTF-8 字节 / 3.8 ≈ token 数                     |
| CompactStrategy      | 策略接口：name() + apply()                              |
| MicroCompactStrategy | 微压缩：截断 >4000 字符的 ToolResultBlock               |
| FullCompactStrategy  | 完整压缩：调小模型对历史消息做摘要                      |
| CompactPrompts       | 摘要 prompt 模板                                        |
| CompactResult        | 结果记录：before/after tokens, 压缩/保留消息数          |

触发条件分两种场景：自动触发（引擎驱动）和手动触发（用户命令）。

1. 自动触发

`CompactPlanner.planAndApply()` 被 `QueryEngine.runTurn()` 在每次 LLM 调用迭代前调用。入口逻辑只有一行判断：

```
 int est = estimator.estimate(session.messages());
  if (!budget.isOverSoft(est)) return false;  // 没超就不做任何事
```

`isOverSoft` 的判断是：

```
 // CompactBudget
  public boolean isOverSoft(int estimatedTokens) {
      return estimatedTokens > softLimit();  // > 170k (200k * 0.85)
  }
```

也就是说：只有当 TokenEstimator 估算的 token 数 > 170,000 时，压缩才会启动。 如果没超过，整个 planAndApply 直接返回 false。超过软限制后，进行 miro 压缩，只要降到软限制以下，立即停止，不再执行后续策略。只有当 miro 压缩后还超过限制，才会进行 full 压缩。

2. 手动触发

/compact 命令 → forceCompact()：无条件执行所有策略各一次，不检查是否超软限制。即使用户只聊了两轮、token 远低于限制，也会跑一遍。区别在于 forceCompact 不会因为"降到软限制以下"而提前退出——每个策略最多执行一次。

### **两种策略**

**MicroCompactStrategy — "微压缩"**

遍历所有消息的所有 ContentBlock，找到 ToolResultBlock，如果内容长度 > microMaxResultChars（默认4000 字符），进行截断：保留头部 75% (3000 chars)，保留尾部 25% (1000 chars)。

不改变消息数量，不碰 TextBlock、ThinkingBlock、ToolUseBlock，不调 LLM，纯 CPU 操作。

应用场景： 大文件读取、长命令输出导致的工具结果膨胀。比如 cat 了一个 2 万行的文件，ToolResultBlock 里存了所有内容，micro 直接砍掉中间部分。

**FullCompactStrategy — "完整压缩"**

如果 micro 压缩后 token 已经降到软限制以下，full 根本不会被调用。只有当 micro 不够用（或没东西可截断）时才会走到 full。

 1. 找分割点 — 从尾部倒扫，找最近 3 条"真实用户消息"（不含 tool_result 的 user 消息）作为分割线，之前的全部压缩，之后的保留。找不到 3 条就退化到 2、1 条。
  2. 调 LLM 生成摘要 — 分割线之前的消息渲染成纯文本，发给小模型，summary prompt 要求保留目标、文件路径、决策、待办事项，输出 ≤ 800 词。
  3. 重建消息列表 — 结果变成 [SystemInit, CompactBoundary摘要, 保留的最近消息]。

## 工具系统

LLM 本身只会输出文本。Function Calling（Anthropic 叫 Tool Use）是让 LLM 输出结构化的"我要调用某个工具"的能力。协议是这样的：

发给 LLM 的请求中包含工具定义（name + description + JSON Schema）：

```json
  {
    "tools": [
      {
        "name": "bash",
        "description": "Runs shell commands...",
        "input_schema": { "type": "object", "properties": { "command": {...} } }
      } 
    ]
  } 
```

LLM 的响应中可能包含 tool_use block：

```json
  {
    "type": "tool_use",
    "id": "toolu_abc123",
    "name": "bash",
    "input": { "command": "ls -la" }
  }
```

Agent 执行后，把结果作为 tool_result 放回对话：

```json
  {
    "type": "tool_result",
    "tool_use_id": "toolu_abc123",
    "content": "total 88\ndrwxr-xr-x  15 ..."
  }
```

MadaCode 中这三个阶段分别对应：

| 协议阶段            | MadaCode 实现                                                |
| ------------------- | ------------------------------------------------------------ |
| 工具定义 → API 请求 | Tool.inputSchema() + AnthropicMessageSerializer 把所有注册工具序列化进请求 |
| tool_use → 解析     | MadaApiClient.parseStreamingResponse() 从 SSE 流中累积 ToolCall(id, name, input) |
| tool_result → 回填  | QueryEngine 把 ToolResult 包装成 ContentBlock.ToolResultBlock 加入消息历史 |

###  Tool 的本质

 Tool 的本质是 Agent 的"手脚"，LLM 是"大脑"，只能思考和输出文本。Tool 是 Agent 与外部世界交互的唯一方式。没有 Tool，LLM 只是一个聊天机器人；有了 Tool，它变成了一个能操作环境的 Agent。

MadaCode 的工具谱系反映了 Agent 需要的几种基本能力：

| Agent 能力   | 对应工具                                      | 为什么需要                     |
| ------------ | --------------------------------------------- | ------------------------------ |
| 感知环境     | FileReadTool, GlobTool, GrepTool              | Agent 需要"看"到代码和文件系统 |
| 修改环境     | FileEditTool, FileWriteTool, BashTool         | Agent 需要"动手"改代码、跑命令 |
| 获取外部信息 | WebFetchTool, McpToolAdapter                  | Agent 需要访问互联网和外部服务 |
| 与用户交互   | AskUserQuestionTool                           | Agent 需要在不确定时"问"用户   |
| 自我规划     | PlanCreate/Get/Update/ListTool, TodoWriteTool | Agent 需要分解复杂任务         |
| 委托子任务   | AgentTool                                     | Agent 需要"分身"处理独立子任务 |
| 持久记忆     | MemorySaveTool                                | Agent 需要跨会话记住信息       |
| 能力扩展     | SkillTool                                     | Agent 需要加载领域专用指令     |

这就是为什么 Tool<I> 接口有 isReadOnly() — 它区分了感知（只读）和行动（写入）。Plan mode 下只允许感知类工具，因为规划阶段 Agent 应该只观察、不动手。

### Tool Schema

Tool Schema 的作用是告诉 LLM "你能做什么"，inputSchema() 不仅是给 Anthropic API 的参数，它本质上是 Agent 的能力边界声明。LLM 看到 schema 后才知道：
  - 这个工具叫什么、做什么
  - 需要传哪些参数、什么类型
  - 哪些参数必填、哪些可选

Schema 写得好不好直接影响 Agent 行为质量。比如 BashTool 的 description 字段标注了 "Short explanation of why this command is needed"，这不是给人看的——是引导 LLM 在调用时解释自己的意图，让用户在权限审批时能理解它要做什么。

### 权限门控

LLM Agent 最大的安全风险是 LLM 被诱导执行危险操作（prompt injection、hallucinated commands）。MadaCode 的 ToolExecutor 管线中有多层防御：

```
Hook 预检 → Schema 验证 → Plan mode 守卫 → 权限审批 → 执行 → Hook 后检
```

其中 权限审批（PermissionGate）是面向用户的最后防线。approvalSignature() 的设计很有意思——BashTool 只用 command 做签名，意味着同一条命令审批一次后后续可复用，但换了命令就要重新审批。这在"Agent 自主性"和"用户控制力"之间取了平衡。

### 并行工具调用

Anthropic API 支持一次返回多个 tool_use（parallel tool use）。ToolOrchestrator 的分段策略是 Agent 框架中的一个重要优化，Tool 执行顺序严格按照模型的返回顺序执行：

```
LLM 返回: [grep("foo"), grep("bar"), edit("file.js"), glob("*.ts")]
         ├────────── safe ────────┤ ├── unsafe ──┤   ├── safe ──┤
                    并行执行             串行执行         串行执行
```

isConcurrencySafe() 接受 input 参数，意味着同一个工具在不同输入下可以有不同的并发策略。比如两个 read 可以并行，但两个 edit 必须串行（可能编辑同一个文件）。

## 权限与取消

权限系统是独立于工具的横切层，在 `ToolExecutor` 执行管道中统一介入：

```
ToolExecutor 执行管道:
    hook.pre  →  输入校验  →  plan mode 检查  →  权限门  →  执行

权限门 (PermissionGate) 内部:
    用户规则匹配 →  Bash 安全检查  →  交互式审批
```

**关键设计决策：**

  - 权限不在工具内部判断：`BashTool` 不知道自己是否被允许执行 rm -rf，这个判断由 `BashSafetyPermissionRule` + `PermissionGate` 统一完成。新工具天然继承整个权限体系。
  - Plan Mode 是安全机制：进入 Plan Mode 后，只允许读工具和任务管理工具，写操作全部拒绝。

`DefaultPermissionGate` 实现了一套可组合的规则引擎：

```
  工具调用请求
      │
      ▼
  ┌─────────────────────────┐
  │ 1. 规则链评估 (有序)      │
  │   ReadOnlyPermissionRule  │  ← 读操作全部自动放行
  │   BashSafetyPermissionRule│  ← 危险命令自动拒绝
  │   用户自定义规则...        │  ← 来自 settings.json
  │                          │
  │   有匹配 → 立即裁决        │
  │   无匹配 → 继续            │
  └──────────┬──────────────┘
             │
             ▼
  ┌─────────────────────────┐
  │ 2. 会话记忆查询           │
  │   approvalKey(tool+input)│  ← 同一个工具+相同输入
  │   命中 → allow(remembered)│   之前批准过，直接放行
  │   未命中 → 继续            │
  └──────────┬──────────────┘
             │
             ▼
  ┌─────────────────────────┐
  │ 3. 用户交互审批           │
  │   ALLOW_SESSION → 记住   │  ← 本次会话不再问
  │   ALLOW_ONCE    → 放行   │  ← 仅此一次
  │   DENY          → 拒绝   │  ← 拒绝并记录
  └─────────────────────────┘
```

## Sub Agent 系统

Sub-Agent（子智能体） 是 LLM Agent 系统中实现"任务分治"的关键机制。它的本质是：把一个独立子任务委托给一个上下文隔离的新 Agent 实例去完成，子 Agent 完成后只把最终结论返回给父 Agent。

为什么需要 Sub-Agent？因为 LLM 有两个核心约束：

    1. Context window 有限：父 Agent 如果直接执行所有探索/搜索操作，会被中间结果（大量文件内容、grep 输出）淹没，挤占真正需要的上下文
    2. 专注力有限：让一个 Agent 同时做"理解需求 + 探索代码 + 实施修改"会让它在角色间反复切换，质量下降

Sub-Agent 解决这两点：子 Agent 的中间结果留在子上下文里，只把浓缩后的答案返回；同时每个子 Agent 有自己的 system prompt 和工具集，专注做一件事。

MadaCode 的 Sub Agent 作为 Tool 通过 `AgentTool` 派生，子 Agent 执行结果作为 `ToolResult` 返回给主 Agent，子 Agent 事件通过 `ParentEventForwarder` 向上冒泡，子 Agent 的进度在主 TUI 中可见。子 Agent 有自己的 `maxIterations`（防止递归爆炸）并且子 Agent 的工具集是受限的（不能 spawn 孙 Agent 无限递归），子 Agent 的结果以 `ToolResult` 形式返回，对主 Agent 来说就是一次普通工具调用。

### 定义：AgentDefinition

每个 sub-agent 类型由一个 AgentDefinition 定义：

```
  new AgentDefinition(
      agentType,         // "explorer" / "planner" / "general"
      description,       // 父 Agent 看到的描述
      whenToUse,         // 父 Agent 决定是否调用时的提示
      systemPrompt,      // 子 Agent 的"性格和职责"
      allowedTools,      // 工具白名单（如 explorer 只有 file_read/glob/grep）
      disallowedTools,   // 工具黑名单（必含 agent，禁止无限递归）
      maxIterations      // 子 Agent 的 turn 循环上限，可为空表示继承默认行为
  );
```

内建的三个 agent 体现了 Capability-Based Agent Design（基于能力的智能体设计）：

| Agent    | 工具集                      | 定位                          |
| -------- | --------------------------- | ----------------------------- |
| explorer | file_read, glob, grep       | 只读探索者 — 找文件、搜代码   |
| planner  | file_read, glob, grep       | 架构分析师 — 分析设计、提建议 |
| general  | file_read, glob, grep, bash | 通用执行者 — 可执行命令       |

注意 explorer 和 planner 工具集完全相同，区别只在 system prompt——同样的工具，不同的"人格"，产出不同。这是 prompt engineering 而非能力差异的运用。

### 调用：AgentTool

AgentTool 是父 Agent 调用 Sub-Agent 的入口，父 Agent 不直接"知道" sub-agent 存在，它只看到一个 agent 工具，schema 是： 

```json
 {
    "name": "agent",
    "input": {
      "description": "Short task description",
      "prompt": "The task for the sub-agent",
      "subagent_type": "explorer | planner | general | ..."
    }
  }
```

`AgentTool.description()` 动态拼接当前可用的 subagent_type 列表注入到 LLM 看到的描述里，这样父 Agent 知道有哪些子 Agent 可调用。

### 执行：AgentRunner

AgentRunner 是 Sub-Agent 的执行引擎，AgentRunner.run() 做了 5 件事：

```java
  // ① 构建子 Agent 的工具子集（过滤 allowed/disallowed，强制移除 agent）
  ToolRegistry childRegistry = buildChildRegistry(definition);

  // ② 用子 Agent 的 systemPrompt 构造独立的 QueryEngine
  QueryEngine childEngine = QueryEngine.builder(
      apiClient, childRegistry,
      new SystemPromptBuilder(definition.systemPrompt()),
      new HeadlessPermissionGate(childToolNames))  // 无人值守权限
      .maxIterations(definition.maxIterations())
      .build();

  // ③ 创建独立的 ConversationSession（消息历史完全隔离）
  ConversationSession childSession = new ConversationSession(parentContext.workingDirectory());
  childSession.setPlanMode(parentSession.isPlanMode());  // plan mode 传染

  // ④ 挂载事件转发器（精选事件冒泡到父）
  childSession.addListener(new ParentEventForwarder(parentSession, parentToolUseId));

  // ⑤ 用 childContext（depth+1）启动子 turn
  ToolUseContext childContext = parentContext.childContext(childSession);
  return childEngine.runTurn(childSession, input, childContext);
```

每个 sub-agent 都是一个完整的 mini MadaCode 实例，拥有独立的 ToolRegistry、独立的 SystemPromptBuilder、独立的 ConversationSession、独立的 QueryEngine、独立的 turn 循环。

### 设计决策

1. **上下文隔离（Context Isolation）**

  子 Agent 用 new ConversationSession() 创建全新的消息历史，不继承父的对话。它只看到：
  - 自己的 system prompt
  - 父传给它的 prompt 作为第一条 user message

父 Agent 在子 Agent 结束后只收到一个 ToolResult（子 Agent 的最终回复文本），中间所有 tool_use / tool_result / thinking 都留在子的消息历史里。这是 sub-agent 的核心价值——父 Agent 的 context window 不会被子任务的中间过程污染。

2. **能力降权（Capability Restriction）**

 子 Agent 的 ToolRegistry 是父的子集，且：
  - 永远移除 agent 工具 → 防止无限递归 spawn
  - 复用父的 PermissionGate，子 session 设为 ACCEPT_EDITS 模式 → 文件编辑自动通过，bash 等高危操作弹审批给用户

这实现了 principle of least privilege：explorer 子 Agent 不能跑 bash 就是不能跑，不依赖 prompt 约束（LLM 可能被骗），而是从注册表层面物理移除。

3. **深度限制（Recursion Depth）**

  `ToolUseContext` 携带 depth 和 maxDepth：

```
  public boolean canSpawnSubAgent() {
      return depth < maxDepth;
  }

  public ToolUseContext childContext(ConversationSession childSession) {
      return new ToolUseContext(..., depth + 1, maxDepth, ...);
  }
```

虽然 `disallowedTools` 已经阻止子 Agent 调用 agent，但 depth 是双保险——即使将来允许 sub-agent 调 agent，也有硬性深度上限。

4. **取消传递（Cooperative Cancellation）**

childContext() 复用父的 `cancellationToken`，用户按 Ctrl+C 时整棵 agent 树同时取消，避免父 Agent 卡死等待子 Agent。

## Long-Running 与 Eval

### Long-Running 模式

Long-Running 不是把普通 turn 简单延长，而是由控制会话和 Worker 会话协同推进的任务运行时。任务状态、阶段转换、租约和 workspace checkpoint 持久化到任务存储中；Worker 使用独立上下文执行阶段性工作，完成后通过报告工具回传结果。监控线程只发布进度和生命周期事件，不直接写入主会话 transcript；恢复时先读取 checkpoint 和任务状态，再重新获取租约继续执行。

### Eval 运行框架

`madacode.eval` 复用生产环境的 `HeadlessAgentRuntime`，因此评测使用真实的 API client、工具注册表、权限规则和 Long-Running runner，避免评测路径与生产路径发生工具漂移。每个 case 可以声明工作区、运行模式、能力要求和验证脚本；运行结果写入带 checkpoint 的 JSON 报告，并可生成 HTML 汇总。执行环境目前包括：

| 环境 | 用途 |
| --- | --- |
| `LocalAttemptExecutor` | 在本机隔离目录执行单个 case |
| `DockerEvalExecutionEnvironment` | 在容器中提供可复现的依赖和边界 |
| `GitWorktreeEvalExecutionEnvironment` | 为仓库型任务创建独立 Git worktree |

这使得普通 turn、Long-Running 任务以及 SWE 类仓库修复都可以沿用同一套评测报告和追踪模型。

## Skill 与 Memory

 LLM Agent 的核心矛盾：**模型权重静态、任务动态、上下文有限**。Skill 解决"领域能力"问题，Memory 解决"持久记忆"问题。

---

  ### Skill 系统

按需加载的"操作手册"——让 LLM 在不微调的前提下临时变成领域专家。学术上叫 **prompt-as-program**。

**数据模型**

  ```java
  record Skill(
      String name, String description, String whenToUse,
      SkillSource source,        // BUNDLED / USER / PROJECT
      String body,                // SKILL.md 正文
      String mode,                // "inline" or "fork"
      List<String> allowedTools,
      Integer maxIterations
  )
  ```

**三层加载链（后者覆盖前者）**

  ```
  BundledSkillLoader              → jar 内置
  DiskSkillLoader(~/.mada/skills) → 用户级
  DiskSkillLoader($PROJECT/...)   → 项目级
  ```

**两种执行模式**

| 模式       | 机制                                      | 适用                           |
| ---------- | ----------------------------------------- | ------------------------------ |
| **inline** | skill 正文塞进父对话，当前 turn 执行      | 轻量工具型（simplify、verify） |
| **fork**   | spawn sub-agent，独立 turn 执行，返回结论 | 流程型（code-review）          |

对应 Agent 学界的 **prompt chaining** vs **hierarchical agents** 两个范式。`SkillTool.description()`会动态列出可用 skill 名字，LLM 看到工具描述自主决定是否调用。

---

  ### Memory 系统

Agent 的三种记忆：

| 记忆类型     | 工程实现                                | 关键约束                     |
| ------------ | --------------------------------------- | ---------------------------- |
| **短期记忆** | `ConversationSession.messages`          | 窗口有限 → 需要压缩          |
| **长期记忆** | `MemoryStore` + `MEMORY.md`（索引文件） | 需要检索机制                 |
| **工作记忆** | `CurrentPlan`（计划项快照）            | 不在 messages 中，独立可修改 |

Plan/Todo 不在 messages 里，而是 session 的独立分支：

  - **运行时**：`ConversationSession.currentPlanRef` 用 `AtomicReference` 维护内存快照
  - **持久化**：随 session 一并序列化到 `~/.mada/sessions/<id>.jsonl`
  - **注入**：每次 turn 启动时，`SystemPromptBuilder` 只把 **active tasks** 注入 。

应为 Plan/Todo 不在 messages 里，所以不会受到 compact 的影响，并且随 session 持久化，崩溃恢复后任务依然可以继续。

**长期记忆的两条来源**

MadaCode 的 memory 系统其实包含两种不同性质的文件：

1. **MADA.md — 人写的项目规则**

定义 Agent 的执行规范和规则，由 MadaMdLoader.load(cwd) 加载，三层搜索：

- ~/.mada/MADA.md                                           ← user-global（全局风格）

- cwd 向上走，找最近的 MADA.md                   ← project-root（团队规则）

- cwd/MADA.md（如果跟前两个不同）            ← cwd-local（当前目录额外约束）

2. **~/.mada/memory/*.md — Agent 自动维护的记忆**

每条记忆是一个独立 .md 文件，带 frontmatter：

```
  ---
  name: feedback-concise
  description: User prefers terse responses without trailing summaries
  type: feedback
  ---
  （正文：详细记忆内容）
```

~/.mada/memory下会有一个索引为`MEMORY.md` ，它是其他 memory 的**目录**而非内容。每次 turn 启动时只注入索引（≤200 行 / 25KB），LLM 看到觉得相关就主动用 `file_read` 展开。

```  
  ~/.mada/memory/
  ├── MEMORY.md              ← 索引（目录）
  ├── user_role.md           ← 记忆 1
  ├── feedback_concise.md    ← 记忆 2
  └── project_overview.md    ← 记忆 3
```

**memory 的两种写入方式：**

1. 用户可以手动导入符合格式的 memory md 文件。
2. 通过下达指定让模型调用 `memory_save` 工具写入，交互时，模型也能通过自己的判断来决定记住哪些内容并调用 `memory_save` 写入，写入后自动 upsert `MEMORY.md` 索引，下次会话自动可见。

## MCP 接入

### 协议

MCP（Model Context Protocol） 是 Anthropic 设计的开放协议——让外部进程（数据库、浏览器、Slack...）以子进程 + JSON-RPC 的方式给 LLM Agent 提供工具和资源。

```
  LLM Agent (host)
      ↕  JSON-RPC over stdio
  MCP Server (subprocess)
      │
      ├─ tools/list   → "我有这些工具"
      ├─ tools/call   → "调用这个工具"
      ├─ resources/list → "我有这些资源（数据）"
      └─ resources/read → "读取资源"
```

MadaCode 把每个 MCP server 当成一个子进程的远程对象，用 JSON-RPC 跟它对话，再把它声明的工具伪装成本地工具注册到全局 ToolRegistry，让 LLM 透明使用。

```  分层架构（每层一个职责）
  LLM 看到的 tool_use
          ↓
  ┌──────────────────────┐
  │ McpToolAdapter       │  把 MCP 工具伪装成本地 Tool<I>
  ├──────────────────────┤
  │ McpClient            │  JSON-RPC 协议（请求路由、握手）
  ├──────────────────────┤
  │ StdioTransport       │  stdin/stdout 字节流
  └──────────────────────┘
          ↓
  子进程（任意语言写的 MCP server）
```

### 工具桥接

MadaCode 通过定义 McpToolAdapter 来将 mcp server 暴露工具包装成正常的 Tool 工具暴露给模型，模型会将这些包装好的mcp tool 视为正常的系统 tool 进行调用。

McpToolAdapter 是一层透明的远程代理（Proxy）—— 对 ToolExecutor 和 LLM 来说，它就是一个普通的 Tool<I>，跟 BashTool、FileEditTool、完全对等地走完整的执行管线（schema 校验 → 权限审批 → plan mode 检查 → hook → execute）。

但 execute() 内部没有任何业务逻辑——它的全部职责就是：

  1. 检查背后的 MCP server 是否还活着
  2. 把 LLM 传入的 input（ObjectNode）连同工具名原封不动转发给 McpClient
  3. 等 McpClient 通过 JSON-RPC 调用真实的子进程
  4. 把响应包成 ToolResult 返回

**三层职责的清晰分工**

| 层             | 角色          | 关注点                          |
| -------------- | ------------- | ------------------------------- |
| McpToolAdapter | Tool 接口契约 | "我是一个工具"                  |
| McpClient      | JSON-RPC 协议 | "我会发请求收响应"              |
| StdioTransport | 字节流传输    | "我会读写子进程的 stdin/stdout" |

每层都不知道下层的细节——adapter 不知道协议是 JSON-RPC，client 不知道传输是 stdio。换 HTTP 传输只动最底层，换协议版本只动中层。

一个 MCP server（比如 GitHub server）通常暴露多个工具（create_issue、list_pr、merge_pr...），MadaCode 给每个工具创建一个独立的 McpToolAdapter，但它们共享同一个 McpClient 实例：

```
     GitHub server 子进程（单实例）
          ↑ 单一 stdio 通道
     McpClient（单实例，按请求 id 路由响应）
          ↑          ↑          ↑
    adapter1   adapter2   adapter3   ...
    (5 个 adapter 都注册进 ToolRegistry)
```

McpClient 用递增的请求 id + Map<id, CompletableFuture> 做多路复用——多个 adapter 可以并发调用，单一 stdio 通道也不会乱。这是 RPC 框架的经典模式（gRPC stub、Java RMI），MCP 桥接本质上就是把这个模式应用到 LLM 工具调用上。
