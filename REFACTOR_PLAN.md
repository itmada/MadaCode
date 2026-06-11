# MadaCode 重构执行计划

> **投喂方式**：每次只把「全局规则」+「一个任务」交给执行模型，任务完成、测试通过、提交后再投喂下一个。
> 不要把整份文档一次性贴给一个 session。任务顺序就是执行顺序，存在依赖关系，不要跳跃。

---

## 全局规则（每个任务都要附带这一节）

1. **本计划显式覆盖 AGENTS.md 中的两条默认约束**：
   - "不默认新增测试" —— 本计划中标注"允许/要求新增测试"的任务，视为用户已明确要求写测试。
   - "不做无关重构" —— 本计划中的任务本身就是重构任务，但每个任务**只做该任务描述的重构**，不顺手改其他东西。
   - AGENTS.md 的其余条款（用 `./mvnw`、Jackson、现有事件/渲染管线、改完自审 diff）继续遵守。
2. 每个任务完成后必须运行 `./mvnw test`，全绿才算完成。若现有测试失败，先判断是测试固化了旧行为还是改坏了，按任务的"验收标准"裁决。
3. 每个任务一个独立 commit，commit message 引用任务编号（如 `P1-2: ...`）。
4. 文档中引用的行号基于编写时的代码，可能漂移。**动手前先读目标文件确认现状**；若现状与任务描述的前提不符，停下来报告差异，不要硬改。
5. 除非任务明确说明，**对外行为（CLI 行为、transcript 文件格式、prompt 内容、工具输出）保持不变**。
6. 禁止引入新的第三方依赖，除非任务明确允许。

---

## 阶段 0：特征测试（后续所有重构的安全网）

### P0-1：QueryEngine 回合循环特征测试 【要求新增测试】

**目标**：用测试固化 `madacode.core.engine.QueryEngine#runTurn` 的现有行为，作为后续重构的基线。

**做法**：新建 `src/test/java/madacode/core/engine/QueryEngineTest.java`。构造一个脚本化的 fake `ApiClient`（实现 `madacode.services.api.ApiClient`，按调用次数返回预设 `ApiResponse`），配最小 `ToolRegistry`（注册一两个简单 fake Tool）、放行一切的 `PermissionGate`、默认 `SystemPromptBuilder`。

**必须覆盖的行为**（以现有实现为准，测试描述现状而非理想）：
- 无 tool call 的响应 → 返回 `FinishReason.COMPLETED`；`StopReason.MAX_TOKENS_REACHED` → `FinishReason.MODEL_TRUNCATED`。
- 有 tool call → 工具被执行，结果以一条 user 消息（内含 `ToolResultBlock`，顺序与 toolCalls 一致）追加到 session，然后进入下一轮迭代。
- 达到 `maxIterations` → session 末尾追加 system 警告消息，返回 `FinishReason.MAX_ITERATIONS`。
- `ApiClient` 抛 `ApiClientException` → 追加 assistant 终止消息，返回 `FinishReason.API_ERROR`。
- 取消：`CancellationToken` 取消后返回 `FinishReason.CANCELLED`；当取消原因为 `CancellationToken.REASON_PERMISSION_DENIED` 时返回 `FinishReason.PERMISSION_CANCELLED` 且**不**发 `MetaEvent.Error`。

**验收**：以上每条至少一个测试方法；`./mvnw test` 全绿；不修改任何 `src/main` 代码（若发现必须改主代码才能测，报告而不是改）。

### P0-2：ToolExecutor 事件时序与准入特征测试 【要求新增测试】

**目标**：固化 `madacode.core.engine.ToolExecutor#execute` 的事件时序契约与各失败路径。注意：`SessionListener.onToolExecutionReached` 的 javadoc 声称存在 `ToolExecutorTest` 验证时序契约，但该测试实际不存在——本任务就是补上它。

**必须覆盖**：
- 成功路径事件顺序：`fireToolExecutionReached` → permission check → `fireToolExecutionStarted` → 工具执行 → `fireToolResultAvailable` → `fireToolExecutionCompleted`（用记录事件序列的 `SessionListener` 断言相对顺序）。
- 未知工具名 → 返回失败 `ToolResult`，**不**触发 `MetaEvent.Error`，仍触发 completed 事件。
- 输入校验失败、permission 拒绝、工具抛异常、工具抛 `CancellationException` → 各自返回失败 `ToolResult` 且 completed 事件被触发。
- plan mode 下非只读且不在 `PLAN_MODE_ALLOWED` 内的工具被拒绝。
- hook：`HookManager` 的 pre/post hook 对名为 `agent` 的工具被跳过（现有行为）。

**验收**：同 P0-1。

### P0-3：ConversationSession 与 SessionStorage 往返特征测试 【要求新增测试】

**目标**：固化消息模型不变量与 transcript 序列化格式，为阶段 3/4 的模型重构提供回归基线。

**必须覆盖**：
- `addMessage` 拒绝连续同 role（SYSTEM 除外），抛 `IllegalStateException`。
- 流打开期间（`beginAssistantStream` 后未 finalize）`addMessage` 抛异常。
- `addControllerEvent`：当末尾是 USER 消息时自动插入 `[controller-event separator]` SYSTEM 消息；事件消息本体是 `[controller-event][domain]` 前缀的 USER 消息；之后跟 `[controller-event barrier]` SYSTEM 消息。
- `enqueueControllerEvent` + `flushPendingControllerEvents` 的排队语义。
- `SessionStorage.save` → `load` 往返：messages、plan items、todos、inputHistory、long-running 字段（以 `serializeSession` 实际包含的字段为准，先读代码再写断言）完整保留。
- `title()` 跳过 controller-event 文本取第一条真实用户输入。

**验收**：同 P0-1。

---

## 阶段 1：小型安全重构（热身，简化 core）

### P1-1：ToolExecutor 失败路径去重

**现状**：`ToolExecutor#execute` 中有 5 个近似相同的代码块：构造失败 `ToolResult` → （可选）记 `DiagnosticEventLogger` → `emitCompleted` → return。

**做法**：抽取私有 helper（形如 `failResult(session, toolCall, toolName, input, message, startNanos)`），统一计时与事件发射，主流程改为调用 helper。不改变任何事件触发顺序、消息文案、诊断日志调用。

**验收**：P0-2 测试不改一行且全绿；`execute` 方法行数显著下降；diff 中无行为变化。

### P1-2：工具的 plan-mode 准入与 hook 豁免声明化

**现状**：`ToolExecutor` 顶部硬编码 `PLAN_MODE_ALLOWED` 工具名字符串集合（含 `longrun_*` 等，core 包泄漏特性知识）；`execute` 中两处 `!tool.name().equals("agent")` 特判控制 hook 跳过。

**做法**：
1. `madacode.tool.Tool` 接口新增 `default boolean isPlanModeSafe() { return isReadOnly(); }`，原集合中列出的每个工具类各自 override 返回 `true`。`ToolExecutor` 改用 `tool.isPlanModeSafe()`，删除 `PLAN_MODE_ALLOWED`。
2. `Tool` 接口新增 `default boolean bypassesHooks() { return false; }`，`AgentTool` override 返回 `true`，`ToolExecutor` 中的名字特判改为读该属性。

**验收**：plan mode 下被允许/拒绝的工具集合与改前完全一致（对照原集合逐一确认 override 已加上）；P0-2 测试通过（其中 plan-mode 与 hook 测试如固化了旧实现细节可同步调整断言方式，但行为断言不变）。

### P1-3：消除工具输入的重复反序列化

**现状**：`ToolOrchestrator#isConcurrencySafe` 对每个 tool call 调一次 `ToolInputCoercion.coerceUnchecked`，随后 `ToolExecutor#execute` 对同一输入再 coerce 一次。

**做法**：让 coercion 结果在编排与执行间复用。推荐方案：`ToolOrchestrator` 在分段前对每个 call 做一次解析，得到内部 `ResolvedToolCall`（持有 tool、原始 input、typed input 或解析失败信息），`ToolExecutor` 提供接受预解析输入的执行入口（保留旧入口委托新入口）。注意：hook 可能修改 `effectiveInput`（`preResult.effectiveInput()`），**hook 改写输入后必须重新 coerce**——保留这条路径。

**验收**：P0-2 全绿；新增一个单测验证 hook 改写输入后工具收到的是改写后的 typed input。

### P1-4：QueryEngine 取消/终止路径收敛

**现状**：`QueryEngine#runTurn` 中 4 处 `cancel.isCancelled()` 检查、3 个 catch 分支各自映射到 `completeWithCancellation` / `completeWithApiError`，逻辑分散。

**做法**：保持检查点位置与语义不变，仅把"终止原因 → 追加消息 + 发事件 + 记日志 + 构造 TurnResult"的映射收敛到一个私有辅助类型或方法组中，消除重复。**不要**改变检查点的位置和次数（取消响应延迟是用户可感知行为）。

**验收**：P0-1 全绿且不修改测试。

---

## 阶段 2：事件机制收敛

### P2-1：DiagnosticEventLogger 从静态调用改为注入

**现状**：`madacode.logging.DiagnosticEventLogger` 被约 10 个文件静态调用，包括 `QueryEngine`、`ToolExecutor`、`ConversationSession`、`SessionStorage`、`MadaApiClient` 等核心类，无法在测试中替换、core 包反向依赖 logging 实现。

**做法**：
1. 定义接口（建议 `madacode.logging.DiagnosticEvents`），方法签名与现有静态方法一一对应；提供默认实现委托现有 logger，另提供 no-op 实现供测试。
2. 按依赖方向逐类改造：`MadaApiClient`、`SessionStorage`、`ToolExecutor`、`QueryEngine` 通过构造注入（在 `bootstrap` 包的 Assembly 中组装）。`ConversationSession` 中 listener 崩溃日志这类点位，若注入成本过高可暂留静态调用，但要在代码注释标注 `TODO(P2-1)`。
3. 静态类保留为兼容 facade，新代码禁止再静态调用。

**验收**：core/engine 与 services/api 包内不再有 `DiagnosticEventLogger.` 静态调用；全部测试通过。

### P2-2：AppEvents 静态单例边界化

**现状**：`madacode.events.AppEvents` 持有 `static volatile AppEventPublisher instance`，与 `SessionListener`/`MetaEvent` 并存为第二套事件系统，集成度低（全仓仅个位数引用）。

**做法**：先 grep `AppEvents` 全部使用点。若使用点都在 bootstrap/CLI 边界，把发布器通过参数传递，删除静态持有；若有深层使用点，改为注入。明确两套系统分工并写入 `events/package-info.java`：会话级（渲染相关）走 SessionListener/MetaEvent；应用级（审计/诊断/致命错误）走 AppEventPublisher。

**验收**：`AppEvents` 静态可变状态被移除（或仅剩 bootstrap 期一次性赋值并有注释说明）；测试通过。

---

## 阶段 3：Session 与 Long-Running 解耦

### P3-1：从 ConversationSession 抽出 SessionEventBus

**现状**：`ConversationSession`（788 行）承载 listener 列表 + 9 个 `fireXxx` 广播方法，session 同时是状态容器和事件总线。

**做法**：
1. 新建 `madacode.core.session.SessionEventBus`：持有 `CopyOnWriteArrayList<SessionListener>`，承载全部 `fireXxx`、`addListener/removeListener`、listener 崩溃保护逻辑。
2. `ConversationSession` 持有一个 `SessionEventBus` 实例并暴露访问器；原有 `fireXxx`/`addListener` 方法保留为委托（避免一次性改全部调用方），标注 `@Deprecated` 引导新代码直接用 bus。
3. `fireMetaEvent` 中"TokenReport 累加 tokenUsage"的逻辑**留在 session**（那是状态更新，不是广播），bus 只做广播。

**验收**：行为不变；P0 系列测试全绿；`ConversationSession` 行数明显下降。

### P3-2：Long-running 状态从 ConversationSession 抽出

**现状**：session 含 8 个 long-running volatile 字段 + `requireLongRunningMode` 校验 + 对 `madacode.longrunning.WorkerReport` 的反向依赖（core → longrunning，方向错误）。

**做法**：
1. 在 `core.session` 定义最小接口或直接新建 `LongRunningSessionState` 类（字段：stage、taskId、taskDirectory、title、reason、planSummary、workerSession、pendingTransitionRequest、lastWorkerReport），连同 `requireLongRunningMode`、`normalizeOptionalLongRunningText` 一起迁入。
2. `ConversationSession` 持有 `volatile LongRunningSessionState longRunning`（可空，COMMON 模式为 null）；`setWorkflowMode(COMMON)` 清空整个对象替代逐字段置 null。
3. 原有 getter/setter 保留为委托以控制改动面，或一次性改完调用方（调用方集中在 Repl、SystemPromptBuilder、longrun 工具、SessionStorage 序列化）——执行时先 grep 评估数量再选。
4. `SessionStorage` 的序列化字段名**保持不变**（transcript 格式兼容）。

**验收**：P0-3 往返测试不改断言全绿；`core.session` 包不再 import `madacode.longrunning`（`WorkerReport` 若被序列化需要，考虑把该 record 移到 core 或用接口隔离——执行时按实际依赖决定并报告选择）。

### P3-3：Repl 中的 long-running 编排抽出为 Coordinator

**现状**：抽象基类 `madacode.cli.Repl`（698 行）约一半是 long-running 编排：completion 队列 drain、stage 流转、transition 确认对话、interrupt 标记、controller event 记录，且 3 处内联 `new LongRunningTaskStore(session.workingDirectory())`。

**做法**：
1. 新建 `madacode.longrunning.LongRunningReplCoordinator`，迁入：`longRunningCompletions` 队列、`drainLongRunningRuntimeCompletions`、`applyLongRunningRuntimeCompletion`、`markLongRunningInterrupted`、`markTaskInterruptedIfNeeded`、`stageFromTaskStore`、`startLongRunningRuntime`、`processPendingLongRunningTransitionRequest`、`handlePendingLongRunningTransitionRequest`、`longRunningTransitionPrompt`、`recordLongRunningControllerEvent` 及相关静态 helper。
2. Coordinator 的依赖（screen、promptChannel、longRunningRuntime、longRunningController、interruptController、persistSession 回调、当前 session 提供器）通过构造注入。注意 session 会被 `replaceSession` 替换，传 `Supplier<ConversationSession>` 而非实例。
3. `Repl` 保留瘦回调点：`handleLine` 首尾调用 `coordinator.drainCompletions()`、turn 结束调用 `coordinator.afterTurn()`。
4. `LongRunningTaskStore` 改为**注入单实例**（在 bootstrap 组装，按 workingDirectory 构造一次），Repl/Coordinator 不再内联 new。全仓搜索 `new LongRunningTaskStore(`（约 10 处），凡在长期存活对象中的内联构造一并改为注入；工具类内部的临时构造可分情况保留并注明原因。

**验收**：`Repl.java` 行数降到 ~350 行以下；long-running 交互行为不变（手动验证 `--long-running` 启动、transition 确认提示仍出现）；测试全绿。

### P3-4：Long-running completion 改为事件驱动（摆脱输入节拍轮询）

**现状**：worker 完成的 completion 进入 `ConcurrentLinkedQueue`，仅在用户敲回车触发 `handleLine` 时 drain——用户不输入，结果就无限期积压不显示。

**做法**：completion 回调到达时（在 runtime 线程上）除入队外，立即通过线程安全的通知路径把摘要推到终端：JLine 场景用 `LineReader#printAbove`（确认 `JLineScreen`/`Screen` 是否已有等价能力，没有则加一个线程安全的 `notifyAsync(String)`）。**状态变更（stage 流转、persistSession）仍然只在主线程 drain 时执行**——异步路径只做用户可见通知，不碰 session 状态，避免破坏单写者模型。

**验收**：模拟 worker 完成后不输入任何内容，终端能看到完成摘要；session 状态流转仍发生在下一次 drain；无并发写 session。

### P3-5：拆分 LongRunningTaskStore（1438 行）

**现状**：单类混合任务 CRUD、状态机校验、原子写文件、文件锁 + 静态进程内锁表、events.jsonl 追加、checkpoint、monitor tail 读取。

**做法**：
1. 新建 `madacode.util.AtomicFiles`（或放 `storage` 包）：统一"写临时文件 → 原子 move → `AtomicMoveNotSupportedException` 降级"逻辑。`LongRunningTaskStore` 与 `SessionStorage.save` 改用它（两处现各有一份实现）。
2. 将 `LongRunningTaskStore` 按职责拆为同包的三个协作类：`LongRunningTaskRepository`（task.json/feature_list/known_issues/progress/checkpoint 的读写与状态校验）、`LongRunningEventLog`（events.jsonl 追加与 tail 读取，含 `APPEND_LOCKS`）、`LongRunningLockManager`（execution/state 文件锁，含 `TASK_STATE_LOCKS`）。`LongRunningTaskStore` 保留为 facade 委托三者，**公共方法签名不变**，调用方零改动。
3. 静态锁表迁移到对应类后行为保持（仍是进程级锁；P3-3 已让 store 单实例化，后续可改实例字段，但本任务不改语义）。

**验收**：所有调用方无 diff；long-running 创建/更新/中断流程手动走通；测试全绿。

---

## 阶段 4：Transcript 模型与持久化（影响最大，前面阶段完成后再做）

### P4-1：会话改为事件日志 + API 投影 【允许新增测试】

**现状与动机**：消息模型把"发给 API 的 wire 消息"与"会话日志"混在一份数据里，导致：`addMessage` 用运行时异常强制"无连续同 role"；controller 事件被编码成 `[controller-event][...]` 前缀的伪 user 消息，还需插入 `[controller-event separator]`/`[controller-event barrier]` SYSTEM 标记消息绕开约束；`AnthropicMessageSerializer` 又要丢弃历史 SYSTEM 消息。证据集中在 `ConversationSession#addControllerEventMessage` 与其 javadoc。

**做法**（分两个 commit）：
1. **引入投影层**：新建 `madacode.services.api.ApiMessageProjection`（或并入 serializer 前置步骤），职责：输入 session 消息列表，输出满足 provider 约束的 wire 消息列表（丢 SYSTEM、合并相邻同 role、controller event 渲染为带 `[controller-event][domain]` 前缀的 user 文本——**前缀必须保留**，`SystemPromptBuilder` 的 long-running 协议明文引用了它）。`QueryEngine` 调 `apiClient.send` 前过一次投影。
2. **放松会话端约束**：投影兜底后，`ConversationSession` 删除 separator/barrier 标记消息的插入逻辑，controller event 改为带类型标记的消息（最小实现：`Message` 增加一个 `kind`/metadata 字段标识 CONTROLLER_EVENT，serializer 据此渲染）；`addMessage` 的连续同 role 异常可降级为投影层自动合并。**transcript 序列化格式变更必须经 `SchemaMigrator` 增加版本迁移**，旧 transcript 必须能加载。
3. P0-3 中固化 separator/barrier 行为的测试在本任务中**有意更新**为新行为；其余测试不改。

**验收**：旧版本 transcript 文件可正常 `--resume`；发往 API 的请求体与改前语义等价（用 fake ApiClient 捕获消息列表对比）；`ConversationSession` 中不再有 `[controller-event separator]`/`barrier` 字符串。

### P4-2：SessionStorage 改为追加式 JSONL 持久化 【允许新增测试】

**现状**：`persistSession()` 在每个 turn 和每条 slash 命令后，用 pretty printer 全量重写整个 transcript JSON——长会话 O(n²) 写放大；`listSessions()` 需解析每个完整文件取摘要。

**做法**：
1. 新格式：`<sessionId>.jsonl`，首行 header（sessionId、createdAt、workingDirectory、schema 版本），其后每行一条消息/事件；可变状态（plan、todos、long-running 字段、inputHistory）写入伴随的 `<sessionId>.state.json`（小文件，全量重写可接受）。
2. `save` 拆为：追加新消息行（记录已持久化的消息数水位）+ 状态文件重写。
3. 加载：识别 `.jsonl` 新格式；旧 `.json` 仍可加载（保留旧 reader），可在加载后顺手迁移为新格式。
4. `listSessions()` 只读 header 行 + 文件元数据，不再解析全文。
5. `crash-safety`：追加使用单 writer、每行 flush；损坏的末行（半行）加载时容忍并截断。

**验收**：新建会话→多轮对话→退出→`--resume` 完整恢复；旧格式 transcript 可加载；P0-3 往返测试更新到新格式后全绿；长会话（构造 500 条消息）下连续 save 的耗时不随消息数线性增长（新增一个简单性能断言或手动验证后在 PR 描述报告数据）。

---

## 阶段 5：独立改进（彼此无依赖，可任选顺序）

### P5-1：MadaApiClient 拆分传输/协议

**做法**：从 `MadaApiClient`（523 行）抽出 `AnthropicStreamParser`（持有现 `parseStreamingResponse`/`handleStreamingData`/`StreamState`/`ToolUseAccumulator`/`extractToolCallInput` 及 required-fields 校验），`MadaApiClient` 只负责构建 HTTP 请求、状态码处理、错误映射、日志。`parseStreamingResponse` 的 4 个重载收敛为 1 个。`resolveMaxTokens` 的 env 读取移到 bootstrap，解析一次后以配置对象注入。

**验收**：流式解析行为不变（建议先为 parser 写测试：text delta、tool_use 增量、thinking、message_delta usage、error 事件）；测试全绿。

### P5-2：SystemPromptBuilder 改为 PromptSection 贡献者模型

**做法**：
1. 6 个伸缩构造函数收敛为一个 + builder。
2. 定义 `interface PromptSection { Optional<String> render(PromptContext ctx); }`（ctx 含 tools、cwd、session）。现有各 section（identity/system/environment/codebase/tools/actions/communication/final/skills/memory/long-running/active-tasks）改为实现该接口，builder 按 bootstrap 注入的有序列表渲染。**long-running 三个 stage 的 prompt 文本移到 `longrunning` 包内的 section 实现**，prompt 包不再含 long-running 知识。
3. 引入 `VisibleTools` 包装类型：仅 `ToolVisibility` 能构造，`SystemPromptBuilder.build` 与 `ApiClient.send` 的工具参数改为该类型，用类型系统固化"调用方必须先过滤"的注释契约。

**验收**：用快照测试对比改造前后同一 session 状态下生成的完整 system prompt，逐字符一致（除非任务中说明的格式差异）；测试全绿。

### P5-3：Markdown 渲染管线收敛

**现状**：四套实现并存——`AnsiMarkdownWriter`（986 行，commonmark→ANSI）、`MarkdownRenderer`（524 行，commonmark 解析）、`InlineMarkdown`（467 行，手写正则内联解析）、`StreamingMarkdownDocument`。手写正则路径与 commonmark 路径的内联语法行为必然不一致。

**做法**：先盘点 `InlineMarkdown` 的全部调用方与其输出差异，然后将其改为基于 commonmark 内联解析的实现（或委托 `AnsiMarkdownWriter` 的内联子集），删除正则解析。保持各调用点的视觉输出不变（粗体/斜体/代码/链接的 ANSI 序列）。**本任务范围只消灭 InlineMarkdown 的独立解析器**，不重排其他渲染类。

**验收**：对一组覆盖内联语法的样例字符串，新旧输出 ANSI 序列一致（先用旧实现生成快照）；TUI 手动目测无差异。

### P5-4：Prompt caching（成本优化，价值高）

**做法**：`AnthropicMessageSerializer.buildRequestBody` 中为 system prompt 块和倒数第二条消息添加 `cache_control: {"type": "ephemeral"}` 断点。仿照现有 `supportsFineGrainedToolStreaming` 的模式，在 `Provider` 配置上加 `supportsPromptCaching` 能力开关（默认 false），仅声明支持的 provider 启用。`TokenUsage` 已有 cacheCreation/cacheRead 字段，确认 `/status` 等展示路径能反映缓存命中。

**注意**：system prompt 每轮迭代重建（stage 可能变化）会击穿缓存——配合实现：build 结果按 session 状态指纹缓存，状态未变时复用同一字符串实例。

**验收**：对接支持缓存的端点时，第二轮请求的 `cache_read_input_tokens` > 0（手动验证并报告）；不支持的 provider 请求体不含 cache_control。

### P5-5：杂项收尾（可合为一个任务）

- **Repl.Config**：30 字段可变 struct 改为 record + builder，非空校验集中。
- **compaction 触发**：`QueryEngine` 每轮迭代无条件调 `compactPlanner.planAndApply`——确认 `CompactPlanner` 内部是否有阈值短路；若每次都做完整评估，把阈值预检提到调用侧，未达阈值不进入。
- **prompt 文本外置**：`SystemPromptBuilder` 各 section 的硬编码文案移到 `src/main/resources/prompts/*.md`，类内仅保留装配逻辑（依赖 P5-2 完成后做）。
- **线程模型文档**：在 AGENTS.md 增加"线程模型"一节：session 单写者（turn 线程）、SessionListener 可能在任意线程回调、UI 输出的线程安全入口是哪个。给 `ConversationSession`（或重构后的类）加 debug 断言（系统属性开关）校验写者线程一致性。

---

## 任务依赖关系速查

```
P0-1, P0-2, P0-3            （无依赖，先做，可并行）
P1-1 ← P0-2
P1-2 ← P0-2
P1-3 ← P0-2
P1-4 ← P0-1
P2-1, P2-2                  ← 阶段1完成
P3-1 ← P0-3
P3-2 ← P3-1
P3-3 ← P3-2
P3-4 ← P3-3
P3-5 ← P3-3
P4-1 ← 阶段3全部 + P0-3
P4-2 ← P4-1
P5-* 相互独立                ← 建议在阶段2后任意时间插入；P5-4 依赖 P5-2 的缓存配合项可后补
```
