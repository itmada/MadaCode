# 模块 05 · Sub-Agent 递归派生

> 源码位置：`agent/AgentRunner.java`（131 行）+ `agent/ParentEventForwarder.java`（91 行）
> 配套精读：`core/engine/ToolUseContext.java`（深度预算 + 通道替换）、`tool/AgentResults.java`（TurnResult → ToolResult）
> 对应简历 bullet："将复杂子任务隔离到独立上下文中执行，子 Agent 继承父会话权限边界，仅将最终结果回传父 Agent，避免子任务过程污染父上下文"
> 前置：01-04 已通关。本模块是你简历上差异化最强的点，多数候选人没有实现过。

---

## 一、30 秒电梯陈述（背下来）

> "`AgentTool` 被调用时触发 `AgentRunner.run()`，它新建一个独立的 `ConversationSession`（独立上下文），用同一个 `PermissionGate`（权限不升级），把父 session 的 planMode / permissionMode / isolationProfile 复制过去，然后起一个完整的 `QueryEngine` 跑子 Agent 的完整 ReAct 循环。子 Agent 的消息、流式输出、工具进度全留在子 session 里，**只有两类事件通过 `ParentEventForwarder` 浮到父层**：token 用量（计费正确）和轻量活动摘要（让父 UI 显示'子 Agent 正在工作'）。子 Agent 跑完，只有最终的 `TurnResult` 作为工具执行结果回到父 Agent，子过程透明不可见。"

---

## 二、三条核心设计原则（面试说三遍）

**1. 独立上下文，过程隔离**
子 session 是 `new ConversationSession(workingDirectory)`，和父 session **完全独立**——子 Agent 的每一轮对话、每一次工具调用、每一条 assistant 消息都在子 session 里，不会污染父 session 的消息列表。父 Agent 模型永远不会看到子 Agent 的内部推理过程。

**2. 共享权限门，权限不升级**
`parentGate` 直接传给子引擎，不 new 一个新 gate。效果：子 Agent 要用危险工具时，权限弹框会显示在**用户的同一个终端**上（不是静默跳过）。更重要的是：`childSession.setPermissionMode(parentSession.permissionMode())`——父是 DEFAULT（每次问用户），子也是 DEFAULT；父是 BYPASS（完全自动），子也是 BYPASS。**派生子 Agent 永远不能提升权限**，这是安全边界。

**3. 结果单向回传，过程不向上冒**
`ParentEventForwarder` 是事件过滤器，明确规定了什么能上冒、什么必须留在子层。必须上冒的只有两类（见下节），其余一律拦截——包括子 Agent 的 `MetaEvent.Error`。为什么连错误都不向上冒？因为父层的 `TurnRenderer` 收到 `MetaEvent.Error` 会立即终止当前回合；子 Agent 挂了（MAX_ITERATIONS、API_ERROR）**不应该直接杀死父回合**，父 Agent 模型应该有机会看到工具执行的失败结果后自己决定下一步。

---

## 三、`ParentEventForwarder` 精读：转发什么，拦截什么

**转发（两类，有明确理由）：**

| 事件 | 为什么转发 |
|------|-----------|
| `MetaEvent.TokenReport` | 子 Agent 消耗的 token 必须反映在父层的计费统计里，否则账单会少算 |
| `onToolExecutionStarted` → 轻量活动摘要 | 让父 UI 的工具卡片显示"子 Agent 正在读取/搜索/运行"，不然父层 UI 看起来像卡死了 |

注意活动摘要是**被投影过的**——`ToolActivitySummary.asProjectionLine(toolName, input)` 把子工具名和参数脱敏成一行文字，不是原始的 tool_result 内容。

**拦截（五类，注释写得非常清楚，建议对照原文读）：**

| 拦截的事件 | 拦截原因 |
|-----------|---------|
| `MetaEvent.Error` | 父层 TurnRenderer 收到它会终止回合，子 Agent 失败不该连坐父 Agent |
| 消息追加/流式事件 | 子对话是私有的，泄漏会污染父消息流，confuse TurnRenderer（它假设只有一条活跃流）|
| 工具原始进度/完成事件 | 子工具的 stdout/stderr 会混入父工具卡片的文本通道 |
| 工具指标事件（METRIC） | 内部测量数据，不是生命周期投影，不该冒泡 |
| Plan/Compact 事件 | 子 Agent 的 plan 是它自己的，不是父的 |

面试时不需要背全部，记住**设计原则是"最小必要上冒"**，只有计费和轻量 UI 反馈两类能穿越边界。

---

## 四、权限继承细节（AgentRunner L84-92）

```java
childSession.setPlanMode(parentSession.isPlanMode());            // plan 模式传播
childSession.setIsolationProfile(parentSession.capabilityProfile().isolationProfile());
childSession.setPermissionMode(parentSession.permissionMode()); // 权限模式传播
```

三行各自的含义：

- **planMode 传播**：父在 plan 模式（只读），子也只读——子 Agent 的写工具被同一个 plan-mode 检查拦截。计划模式是"全局安全承诺"，子 Agent 不能绕开。
- **isolationProfile 传播**：文件系统隔离范围从父继承，子 Agent 不能访问父 Agent 没有权限访问的目录。
- **permissionMode 传播**：这是最关键的——父 DEFAULT 子也 DEFAULT（用户仍然需要逐一审批危险操作），父 BYPASS 子也 BYPASS（全自动跑）。子 Agent 是父 Agent 权限的下界，永远不会是上界。

---

## 五、两条独立的用户通道（这一点最能体现设计深度，建议主动抛）

`childContext()` 里有一行容易被忽略的代码（[ToolUseContext.java:132-135](../src/main/java/madacode/core/engine/ToolUseContext.java#L132)）：

```java
// Sub-agents must not prompt the main user.
return new ToolUseContext(
        workingDirectory, childSession, depth + 1, maxDepth,
        cancellationToken, UnavailablePromptChannel.INSTANCE, ...);
```

子 Agent 的 `userPrompts` 被替换成 `UnavailablePromptChannel`——**子 Agent 不能主动向用户提问**（`ask_user_question` 这类工具在子层直接失效）。

但第四节刚说过：子 Agent 做危险操作时，权限弹框**仍然**会显示在用户终端上。两句话不矛盾吗？

不矛盾，因为这是**两条完全独立的通道**：

| 通道 | 持有者 | 子 Agent 里的状态 |
|------|--------|------------------|
| `context.userPrompts()` | `ToolUseContext` | ❌ 被换成 `UnavailablePromptChannel` |
| `UserApprovalPrompt` | `DefaultPermissionGate` 自己注入的字段（[DefaultPermissionGate.java:42](../src/main/java/madacode/permission/DefaultPermissionGate.java#L42)） | ✅ 随共享 gate 一起继承，照常弹框 |

权限审批走的是 gate 内部的 `prompt.requestApproval(...)`，根本不经过 `ToolUseContext`。所以两者可以独立开关。

**设计意图（面试就这么说）**：

> "子 Agent 不能拿'我需要澄清一下需求'来打断用户——那是父 Agent 的职责，子 Agent 拿到的 prompt 里就该包含它需要的全部上下文；但它做危险操作时**必须**过用户这一关。**主动打扰 = 禁止，安全审批 = 强制**。这两个诉求方向相反，所以必须建模成两条通道，塞进同一个通道就只能二选一了。"

---

## 六、工具能力限制（L102-108）

```java
ToolCapabilityProfile childProfile = !definition.allowedToolsSpecified()
        ? ToolCapabilityProfile.subAgentUnrestricted(agentType, disallowedTools)
        : ToolCapabilityProfile.subAgentRestrictedAllowList(agentType, allowedTools, disallowedTools);
ToolUseContext childContext = parentContext.childContext(childSession, childProfile);
```

子 Agent 的工具能力由 `AgentDefinition` 的 `allowedTools`/`disallowedTools` 决定：
- 没有指定 allowlist → `subAgentUnrestricted`：继承所有工具，只排除 disallowedTools；
- 指定了 allowlist → `subAgentRestrictedAllowList`：只能用 allowlist 里的工具（再减去 disallowedTools）。

注意：工具能力限制通过 `ToolCapabilityProfile` 传递给 `ToolUseContext`，最终在 `ToolAccessResolver` 的访问检查里生效——**底层工具注册表（fullRegistry）是共享的，但访问层决定子 Agent 能看见和调用哪些**。这是"共享目录，访问受控"的设计。

---

## 七、递归：机制留全，策略收紧到单层

**先说结论，别背错**：架构上递归是自然支持的，但**当前深度上限硬编码为 1，只允许单层派生**。

### 机制层面：没有一行代码是为递归特殊写的

子 Agent 的 `childEngine` 就是一个完整的 `QueryEngine`，它的工具注册表里同样包含 `AgentTool`。`ParentEventForwarder` 也实现了 `onToolExecutionActivity`（L84-89），专门用于让孙 Agent 的活动摘要**逐级向上冒泡**到根 Agent 卡片。递归所需的机制是齐的。

### 策略层面：`maxDepth = 1`

```java
// ToolUseContext.java:31 —— 唯一的根构造入口，maxDepth 硬编码为 1
public ToolUseContext(Path workingDirectory, ConversationSession session) {
    this(workingDirectory, session, 0, 1, ...);   // depth=0, maxDepth=1
}

// :134 —— 子 context 只 +1 depth，maxDepth 原样继承
new ToolUseContext(workingDirectory, childSession, depth + 1, maxDepth, ...);

// :94
public boolean canSpawnSubAgent() { return depth < maxDepth; }
```

全仓库 `maxDepth` 没有任何一处被改写（生产环境唯一的 `new ToolUseContext` 调用点是 `QueryEngine.java:149`，走的就是上面这个硬编码构造器）。实际效果：

| 层级 | depth | `canSpawnSubAgent()` | 结果 |
|---|---|---|---|
| 根 Agent | 0 | `0 < 1` | ✅ 可以派生 |
| 子 Agent | 1 | `1 < 1` | ❌ `"Maximum agent depth reached"` |

`ToolUseContext.java:26` 的注释本身就写着 "single-level spawn budget"。

### 面试就这么讲（把限制讲成设计）

> "架构上递归是自然支持的——子 Agent 的引擎里就有 `AgentTool`，代码里没有任何一行是为递归特殊写的。但**深度上限我刻意设成了 1**，理由有两个：一，递归派生的 token 成本是指数级的，二层就可能失控；二，深层子 Agent 的活动摘要冒泡到根卡片后，用户根本分不清是谁在干什么，UI 语义就废了。所以我的做法是**机制留全、策略先收紧**——`ToolUseContext` 完整带着 depth/maxDepth 两个参数，forwarder 也支持逐级冒泡，想放开只要改一个构造参数，不用动任何结构。"

> ⚠️ **千万不要说"无限递归"**。面试官下一句一定是"深度上限是多少、在哪配置"，答不上来就从加分项变成减分项。上面这个讲法反而能体现**成本意识**和**机制/策略分离**的判断力。

---

## 八、已知边界与改进方向（主动讲，别等被挖）

这三条都是真实缺陷。面试时**主动抛出来**比被追问出来强得多——能同时证明三件事：你熟自己的代码、你有架构判断力、你知道当初为什么妥协。

### 8.1 失败时中间成果全丢

`TurnResult` 只有四个字段：`finalText / finishReason / iterations / apiFailure`——**没有消息列表，没有工具结果**。而失败路径的 `finalText` 是什么：

| 失败原因 | 父 Agent 收到的全部内容 |
|---|---|
| `MAX_ITERATIONS` | `"(Reached max iterations: 30)"` |
| `CANCELLED` | `"(Cancelled: <reason>)"` |
| `PERMISSION_CANCELLED` | `"(Permission denied)"` |
| `API_ERROR` | 错误消息 |

也就是说：**子 Agent 跑了 29 轮、读了 40 个文件、grep 出关键线索，第 30 轮触顶——父 Agent 拿到的是一句 `"Sub-agent did not complete (MAX_ITERATIONS): (Reached max iterations: 30)"`，29 轮成果一个字都没有。**

`MAX_ITERATIONS` 尤其恶劣，因为它**恰恰在子 Agent 干活最多的时候触发**。父模型收到这句话，唯一合理的反应是重跑一遍——再烧一遍 token，可能再次触顶。

**改法**：`TurnResult` 加一个 `partialWork` 字段，失败分支把末 N 轮的活动摘要（不是完整 transcript）带回去。不破坏隔离原则（仍然只回传结果，不回传对话），父模型至少有东西可续。

### 8.2 子会话不落盘

落盘不是靠监听器，是**显式调用** `sessionStorage.save(session)`。全仓库的调用点只有 `Repl.persistSession()`（存字段 `session`，硬绑主会话）、几个 slash command（同样是主会话）、以及 `LongRunningWorkerRunner:178`。

`AgentRunner` **连 `SessionStorage` 的引用都没有**——构造器只有 `fullRegistry / apiClient / parentGate / toolAccessResolver`。子 session 没有任何路径能走到 `save()`。叠加 8.1，那些中间成果不是"父看不到"，是**物理上不存在了**。

**但这是有意的选择，有个很好的对照**：`LongRunningWorkerRunner` 同样是"新建独立 session 跑子任务"，结构与 `AgentRunner` 平行，却**显式落盘**，还在 save 失败时往 task store 补一条 `worker_session_save_failed` 事件。同一代码库两个平行场景，一个存一个不存，因为语义不同：

- **Worker session** = 长任务的断点恢复单元，跨进程存活，必须能 `--resume`
- **Sub-agent session** = 一次工具调用的内部实现，生命周期不该超出那次调用

面试讲这个对照，能证明你不是"忘了存"而是分清了两种子会话。

### 8.3 `TokenReport` 双职责导致父界面进度条被误刷

同一个 `TokenReport` 事件被两个消费者用**两种语义**处理：

| 消费者 | 操作 | 语义 | 作用域 |
|---|---|---|---|
| `ConversationSession.fireMetaEvent` | `.plus()` 累加 | 累计花费（`/cost`） | 本该进程级 |
| `MetaEventRenderer` | `setTokens()` **覆盖** | 当前窗口占用（进度条） | 会话级 |

`MetaEventRenderer` 全局只有一个实例，装在主 session 上。`ParentEventForwarder` 把子 Agent 的 `TokenReport` **原样转发**（没有任何标记），父 session 广播 → renderer 收到 → `setTokens(子Agent的窗口占用)`。

**结果**：子 Agent 每完成一次模型调用，主界面的上下文窗口进度条就被子 Agent 的数字覆盖一次。子 Agent 是全新 session、上下文很小，父可能已用 70%——进度条会跳到 10%，等父 Agent 下一轮才弹回去。

对累计计费这是对的（子 token 确实该进总账），对进度条是错的（父的窗口占用没变）。

**根因**：计费是**进程作用域**，窗口刻度是**会话作用域**，两个职责挤在同一个事件上，于是只能选会话作用域再靠 forwarder 补全局。

**改法**：计费下沉成进程级 `TokenLedger`（`AssistantTurnWriter` 直接写），`TokenReport` 只保留会话窗口语义——那条转发规则就可以**整条删掉**，bug 自动消失，`ParentEventForwarder` 也从"转发两类"降到"转发一类"，更纯粹。

> 📌 这条没有实跑验证，是从代码路径推的（单例 renderer + 无差别转发 + setTokens 覆盖语义）。面试可以说"我推断会有这个问题，还没实测确认"——比言之凿凿更稳。

---

## 九、面试官追问预案

**Q1：子 Agent 挂了（比如 MAX_ITERATIONS），父 Agent 怎么知道，会怎么处理？**
参考：子 Agent 的 `MetaEvent.Error` 被 `ParentEventForwarder` 拦截——不上冒。`AgentTool` 把 `TurnResult`（包含 `FinishReason.MAX_ITERATIONS`）转换成一个失败的 `ToolResult` 回传给父 Agent，父模型在下一轮看到"子 Agent 执行失败：超出最大轮次"的工具错误，自己决定是重试、换策略还是报错给用户。父回合不受影响，控制权留在父模型手里。
⚠️ 但如果被追问"父 Agent 具体拿到了什么内容"，**要老实说中间成果全丢了**——`TurnResult` 只带 `finalText`，失败时就是一句 `(Reached max iterations: 30)`，子会话也不落盘。详见 8.1/8.2，那里有完整的讲法和改进方案。

**Q2：子 Agent 能做父 Agent 没权限做的事吗？**
参考：不能，有三道防线。一，权限门是共享的同一个 `parentGate`，危险操作仍然弹框给用户；二，`permissionMode` 严格从父继承，子 Agent 不能切换到比父更宽松的模式；三，`ToolCapabilityProfile` 还可以进一步收窄子 Agent 的工具集。安全是下界传播的，不存在"通过派生子 Agent 绕开限制"。

**Q3：父 Agent 为什么看不到子 Agent 的推理过程？这是设计决定还是限制？**
参考：是设计决定。子 session 独立，`ParentEventForwarder` 只转发最小必要事件。原因：父模型不需要也不应该看到子的内部对话——那会污染父的消息列表、干扰父的决策；用户也只需要知道"子在工作"（轻量活动摘要），不需要看子的完整执行日志。隔离是信息架构的边界，不是偷懒的副产品。

**Q4：token 为什么要上冒，消息内容为什么不上冒？**
参考：token 上冒是**计费正确性**的要求——子 Agent 的模型调用消耗了 token，这个用量必须反映在总计费里，否则账单少算。消息内容不上冒是**上下文隔离**的要求——父模型的上下文窗口是有限的，让子对话进来会挤压父的有效上下文；而且父模型在看到子的内部推理后可能会受干扰，做出本不该做的决策。两者的上冒决策都是由"这个信息对父层有什么语义价值"驱动的，不是技术限制。
补充加分：这条转发其实有副作用——`MetaEventRenderer` 也消费 `TokenReport` 来刷上下文窗口进度条，而且是**覆盖**语义，所以子 Agent 的 token 会误刷父界面。根因是这个事件被计费（进程级）和窗口显示（会话级）两个职责过载了。详见 8.3。

**Q5：如果子 Agent 的工具调用需要用户审批，弹框在哪里显示？**
参考：共享 `parentGate`，弹框在父层（用户的终端）显示。子 Agent 不能绕过审批，也不会弹出第二个终端窗口——用户在同一个界面里审批所有层级的工具调用，体验是统一的。
补充加分（第五节）：注意子 Agent 的 `context.userPrompts()` 是被换成 `UnavailablePromptChannel` 的，也就是子 Agent **不能主动向用户提问**。审批之所以还能弹，是因为它走的是 `DefaultPermissionGate` 自己持有的 `UserApprovalPrompt`，跟 `ToolUseContext` 是两条独立通道。主动打扰禁止、安全审批强制——方向相反的两个诉求，必须拆成两条通道。

**Q6：子 Agent 还能再派生子 Agent 吗？深度上限是多少？**
参考：机制上可以（子引擎里就有 `AgentTool`，forwarder 也支持逐级冒泡），但**当前 `maxDepth` 硬编码为 1，只允许单层**，第二层会拿到 `"Maximum agent depth reached"` 的失败 ToolResult。这是刻意的策略选择：递归派生 token 成本指数级增长，且深层活动摘要冒泡到根卡片后用户无法分辨来源。机制留全、策略收紧，放开只需改一个构造参数。详见第七节。

---

## 十、自查清单

- [ ] 我能说出子 session 独立带来的两个好处（父上下文不被污染 / 子失败不连坐父）
- [ ] 我能背出 planMode、permissionMode 传播的含义
- [ ] 我能说出 `ParentEventForwarder` 转发哪两类、拦截哪五类，以及关键的"为什么拦截 Error"
- [ ] 我能解释 `fullRegistry` 共享但访问受 `ToolCapabilityProfile` 控制
- [ ] 我能说清"子 Agent 不能主动提问、但审批照弹"为什么不矛盾（两条独立通道）
- [ ] 我能说出递归是"机制留全、策略收紧到 maxDepth=1"，并讲出收紧的两个理由
- [ ] 我能回答"子 Agent 挂了父怎么知道"（通过 ToolResult 失败，不是 MetaEvent.Error）
- [ ] 我能主动讲出三条已知边界，每条都带"根因 + 改法"（8.1 成果丢失 / 8.2 不落盘及 worker 对照 / 8.3 TokenReport 双职责）

读完源码回来说"05 读完了"。
