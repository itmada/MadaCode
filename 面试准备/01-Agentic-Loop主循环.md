# 模块 01 · Agentic Loop 主循环

> 源码位置：`src/main/java/madacode/core/engine/QueryEngine.java`（334 行，一个下午能读透）
> 配套类：`core/turn/TurnResult`、`core/model/FinishReason`、`core/session/AssistantTurnWriter`
> 建议：开着 IDEA，按本文的"走读"章节逐段对照，每读一段在代码里打个书签。

---

## 一、30 秒电梯陈述（背下来）

> "MadaCode 的主循环在 `QueryEngine.runTurn()` 里，本质是一个 **while 循环驱动的状态机**：每轮先做取消检查和上下文压缩检查，然后重建系统提示词、调模型，模型要么直接给出文本（回合结束），要么请求工具调用——工具执行结果以 tool_result 回填进会话，进入下一轮，直到模型不再要工具。所有退出路径——正常完成、被截断、取消、权限拒绝、API 错误、超轮次上限——**统一收敛到六种 FinishReason 终态**，终态会作为一条 assistant 消息持久化进会话文件，所以任何一种退出方式之后都能断点续聊。"

这段话里每个短语都对应一段代码，下面逐一落实。

---

## 二、类地图：谁负责什么

```
QueryEngine.runTurn()                 ← 主循环（本模块主角）
 ├─ ConversationSession               ← 消息列表 + JSONL 持久化（会话即事实源）
 ├─ CompactPlanner                    ← 压缩决策（模块 04 细讲）
 ├─ SystemPromptBuilder               ← 每轮重建系统提示词
 ├─ ApiClient                         ← 模型 API（流式，SSE）
 │   └─ AssistantTurnWriter           ← 一次模型迭代内 assistant 消息的生命周期管家
 ├─ ToolOrchestrator → ToolExecutor   ← 工具编排与执行（模块 02 细讲）
 ├─ CancellationToken                 ← 取消信号（模块 03 细讲）
 └─ TurnResult / FinishReason         ← 回合结果 + 六种终态枚举
```

关键认知：**QueryEngine 自己几乎不持有状态**——消息在 `ConversationSession` 里，取消在 `CancellationToken` 里，压缩策略在 `CompactPlanner` 里。它是纯编排者。这是面试时可以主动讲的设计点："引擎无状态，所以同一个引擎实例可以跑父会话也可以跑子 Agent 会话。"

---

## 三、主循环走读（对照 QueryEngine.java L147-276）

### 入口（L147-168）

```java
public TurnResult runTurn(ConversationSession session, String userInput, ToolUseContext ctx)
```

三件事：把用户输入包成 `Message.user()` 追加进会话（**先入会话再进循环**，所以即使第一轮就失败，用户消息也已持久化）；现场 new 出 `ToolExecutor` 和 `ToolOrchestrator`（每回合新建，无跨回合状态）；从 ctx 取出 `CancellationToken`。

### 循环头：两道闸门（L171-189）

```java
while (maxIterations == null || iteration < maxIterations) {
    if (cancel.isCancelled()) { return completeWithCancellation(...); }
    if (compactPlanner != null && compactPlanner.shouldCompact(session)) {
        compactPlanner.planAndApply(session, session::fireMetaEvent, cancel);
        if (cancel.isCancelled()) { return completeWithCancellation(...); }  // ← 注意这里
    }
```

**第一道闸**：每轮开头查取消。
**第二道闸**：查是否需要压缩。这里有两个面试高光细节：

1. **压缩失败不终止回合**。源码注释（L176-181）原文逻辑："超过软阈值不代表超过硬上限；如果真超了，provider 会拒绝请求，回合以干净的 API error 结束——不会陷入注定失败的重试循环（no doomed retry loop）。" 这是一个刻意的 fail-open 决策。
2. **压缩后再查一次取消**。因为压缩本身要调模型 API（做摘要），可能耗时数秒——如果用户在压缩期间按了 Esc，应该立刻兑现取消，而不是再发起一次注定被无视的模型请求。**"在每个耗时操作之后重新检查取消信号"** 是贯穿全项目的模式。

### 每轮重建可见工具与系统提示词（L192-198）

```java
var visibleTools = toolAccessResolver.visibleTools(toolRegistry.tools(), ctx.toolAccessScope());
String systemPrompt = systemPromptBuilder.build(visibleTools, session.workingDirectory(), session);
```

为什么不在回合开始时建一次？源码注释（L192-194）给了答案：**回合内工具可见性会变**——比如 long-running 模式下 `longrun_environment_update` 完成一个任务后，下一轮模型能看到的工具集合和提示词就应该反映新阶段。代价是每轮多一次字符串构建，换来的是"模型永远看到最新世界观"。

### AssistantTurnWriter：本文件最精妙的 30 行（L200-230）

```java
try (AssistantTurnWriter writer = AssistantTurnWriter.open(session)) {
    try {
        response = apiClient.send(..., writer.sink(), cancel);
    } catch (ApiClientException e) {
        writer.abandon(); ...
    } catch (CancellationException e) {
        writer.abandon(); ...
    }
    writer.commit();
}
```

背景问题：模型是**流式输出**的。流到一半失败了，会话里就残留半条 assistant 消息；如果错误处理路径忘了清理，接着又 append 一条错误 assistant 消息，会话里就出现**两条连续的 assistant 消息——下一轮请求会被模型 API 直接拒绝**（Anthropic 协议要求 user/assistant 交替）。

`AssistantTurnWriter` 用 try-with-resources 把这族 bug 一次性消灭（见 `AssistantTurnWriter.java` 类注释）：

- `commit()`：流成功完成 → 把流式内容定稿为正式 assistant 消息。
- `abandon()`：任何失败路径 → 丢弃半成品。
- `close()`（安全网）：如果既没 commit 也没 abandon 就走到了 close（比如漏写的异常路径），**默认 abandon，绝不静默 commit**。

面试金句："我把'半条流式消息该不该进会话'这个决策收敛到一个 AutoCloseable 里，方向是 fail-safe——宁可丢弃也不静默提交脏状态。"

### API 异常的三层分类（L210-228）

| 捕获 | 处理 | 细节 |
|------|------|------|
| `ApiClientException` | 先查是否其实是取消 → 否则 `ApiFailureClassification.classify(e)` 分类后收敛为 API_ERROR 终态 | 取消优先：用户按 Esc 常表现为连接中断异常，**要归因为"用户取消"而不是"API 错误"** |
| `CancellationException` | 直接收敛为取消终态 | 流式读取中检测到取消信号时抛出 |
| `RuntimeException` | 同样先查取消，再收敛为 API_ERROR | 兜底，未知异常也不会让循环崩溃泄露 |

共同点：**每条路径都先 `writer.abandon()`**，然后走 `completeWith*` 收敛——没有任何一条异常路径会让会话处于配对残缺状态。

### 两个正常出口（L232-261）

**出口 A：模型没有请求工具（L232-240）** → 回合自然结束。注意一个细节：

```java
FinishReason finishReason = response.stopReason() == StopReason.MAX_TOKENS_REACHED
        ? FinishReason.MODEL_TRUNCATED : FinishReason.COMPLETED;
```

模型因 max_tokens 被截断和正常说完话，是**两种不同终态**——上层 UI 可以据此提示"回答被截断"。很多 Agent 实现漏掉这个区分。

**出口 B：工具请求了让渡控制权（L252-261）**。工具结果可携带 `TurnControl.YIELD_TO_RUNTIME`——典型如进入 long-running 模式的工具：它要求主循环立刻结束、把控制权交还给运行时（由 Launcher 接管）。这是"工具能反向控制引擎"的通道。

### 工具结果回填（L242-250）

```java
List<ToolResult> results = toolOrchestrator.run(toolCalls, executionCtx);
for (int j = 0; j < toolCalls.size(); j++) {
    toolResultBlocks.add(new ContentBlock.ToolResultBlock(
            toolCalls.get(j).id(), result.output(), result.success(), -1));
}
session.addMessage(Message.user(toolResultBlocks));
```

三个可讲的点：

1. **按索引严格配对**：第 j 个 tool_result 必然对应第 j 个 tool_call 的 id，无论工具是并行还是串行执行的（顺序保证在 ToolOrchestrator 内部完成，模块 02）。
2. **tool_result 装在 user 角色消息里**——这是 Anthropic 消息协议的规定（工具结果视为"用户侧回传"），不是随意选择。
3. 回填后 `flushPendingControllerEvents()`，再查一次取消，然后 `iteration++` 进入下一轮。

### 最后的出口：轮次上限（L270-275）

`maxIterations` 可设可不设（`unlimitedIterations()`）。到达上限时写入一条 system 警告消息 + `MAX_ITERATIONS` 终态。这是防失控的最后保险丝。

---

## 四、六种终态与"统一收敛"（L280-329）

```java
public enum FinishReason {
    COMPLETED,            // 模型自然说完
    MODEL_TRUNCATED,      // 模型被 max_tokens 截断
    MAX_ITERATIONS,       // 轮次保险丝熔断
    API_ERROR,            // 模型请求失败（含分类信息）
    CANCELLED,            // 用户主动取消
    PERMISSION_CANCELLED  // 权限对话框里选择了拒绝并终止
}
```

所有异常出口都汇入同一个漏斗 `completeTerminal()`（L301-309）：

```java
session.addMessage(Message.assistantTerminal(outcome.message(), outcome.finishReason()));
```

**这行是"断点恢复"能力的根**：终态不是仅存在于内存的返回值，而是一条带 FinishReason 标记的 assistant 消息，随会话 JSONL 一起落盘。重新打开会话时，转录是完整的（user/assistant 配对干净、终态明确），可以直接续聊。

再看一个精细区分（`TerminalOutcome.cancellation()`，L319-328）：权限拒绝（`PERMISSION_CANCELLED`）**不触发 Error 元事件**，普通取消触发。为什么？用户在权限框点"拒绝"是正常交互，不是错误，UI 不应该红字报错。——这种粒度的用户体验判断是"真做过"的证据。

---

## 五、面试官追问预案（先自己答，再看参考）

**Q1：你的 Agent 主循环和 LangChain 的 AgentExecutor 有什么区别？为什么不用框架？**
参考：核心区别在控制粒度。我需要的几个能力——流式输出中途取消并保持消息配对、回合内动态刷新工具可见性、工具结果反向让渡控制权（YIELD_TO_RUNTIME）、终态持久化断点恢复——在框架里要么没有，要么要跟框架的抽象搏斗。主循环本身只有 100 多行，自己写的成本远低于跟框架搏斗的成本，而且每一行行为都可解释。

**Q2：模型流式输出到一半网络断了，你的会话会怎样？**
参考：`AssistantTurnWriter` 是 try-with-resources 的，任何异常路径都会 `abandon()` 丢弃半条消息；即使某条路径漏写了，`close()` 安全网默认 abandon。所以会话里永远不会残留半条 assistant 消息，也不会出现两条连续 assistant 消息导致下一轮请求被 API 拒绝。

**Q3：为什么压缩失败了还继续发请求？不怕浪费一次 API 调用吗？**
参考：超过软阈值 ≠ 超过硬上限，多数情况下请求仍会成功；如果真超限，provider 拒绝，回合以干净的 API_ERROR 终态结束，用户看到明确错误。反过来如果压缩失败就终止，会把一个"可能没问题"的回合变成"必然失败"。这是 fail-open 的取舍，且注释里写明了不会形成 doomed retry loop——因为失败终态不会自动重试。

**Q4：取消检查为什么散布在四五个位置，而不是一个统一的检查点？**
参考：取消的及时性取决于检查密度。原则是"每个耗时操作之后必查"：循环头、压缩后（压缩要调模型，数秒级）、API 异常时（区分取消和真错误）、工具执行后。漏掉任何一个点，用户按 Esc 后就会多等一个耗时操作的时间。

**Q5：tool_result 为什么放在 user 消息里？**
参考：Anthropic 消息协议规定 tool_result block 必须出现在 user 角色消息中，与 assistant 消息里的 tool_use block 按 id 配对。这也是为什么中断和压缩都要小心维护配对——配对破损 API 直接报 400。

**Q6：如果模型一直请求工具停不下来怎么办？**
参考：两层防护——`maxIterations` 保险丝（到达后写入 system 警告并以 MAX_ITERATIONS 终态结束），以及用户随时可以取消（CancellationToken 贯穿全链路）。

**Q7：断点恢复具体怎么实现的？**
参考：会话即事实源。每条消息（包括终态 assistant 消息）都追加写入 JSONL（`SessionStorage`）；恢复时重放 JSONL 重建 `ConversationSession`，因为所有退出路径都保证了配对完整 + 终态落盘，恢复后可直接续聊，不需要修复逻辑。

**Q8（压力题）：这段代码里你现在觉得最值得重构的是什么？**
参考（示例姿态，可换成你自己的观察）：`runTurn` 里 try-catch 嵌套较深，异常分类逻辑可以抽成策略；另外 `ToolExecutor` 每回合 new 一次虽然保证了无状态，但和 DI 容器"显式装配"的理念略有出入，可以讨论是否提升为回合工厂。——面试时主动说出改进点比说"没有缺点"可信十倍。

---

## 六、自查清单（全部能脱稿回答才算过关）

- [ ] 我能画出 runTurn 的流程图（两道闸门 → 重建提示词 → 流式请求 → 两个出口/回填循环）
- [ ] 我能说出六种 FinishReason 以及各自的触发条件
- [ ] 我能解释 AssistantTurnWriter 防的是哪一族 bug、安全网方向为什么是 abandon
- [ ] 我能解释为什么压缩后要再查一次取消
- [ ] 我能解释为什么每轮重建 systemPrompt 和 visibleTools
- [ ] 我能解释 YIELD_TO_RUNTIME 的用途和典型场景
- [ ] 我能解释 tool_result 为什么在 user 消息里、配对破损会发生什么
- [ ] 我能说出一个自己认可的改进点

读完源码后，回到对话里说"01 模块读完了"，我来考你。
