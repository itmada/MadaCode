# 模块 06 · Long-Running 模式

> 源码位置：`longrunning/` 包（6284 行，但面试只需要讲清楚三个概念）
> 对应简历 bullet："任务状态持久化 + 控制/执行平面分离，Launcher 引擎动态预算轮次调度 Worker Agent，支持中断恢复与实时进度监控，可无人值守执行小时级任务"
> 前置：01-05 已通关。本模块面试问到的概率是"中等"，但结构新颖，一旦被问会深挖。

---

## 一、30 秒电梯陈述（背下来）

> "Long-Running 是针对小时级任务设计的无人值守模式。核心是**控制/执行平面分离**：控制 session 持有用户对话上下文，Launcher 是一个系统级调度器（不是工具、不被模型调用），它循环起 Worker Agent——每个 Worker 是独立 session，读 task store、做一段有界工作、调 `worker_report` 汇报，然后退出。Launcher 根据 Worker 的报告决定继续、停止还是升级到用户。动态预算公式是 `min(hardLimit, max(1, featureCount + issueCount))`——任务列表越长允许跑的周期越多。任何时刻进程被杀，恢复时 `LongRunningSessionRecovery.recover()` 检测到 RUNNING 状态的残留任务，触发 `PROCESS_RESTARTED` 把它迁移到 INTERRUPT——防止死 launcher 复活。"

---

## 二、三层架构（最重要的概念图）

```
控制层（Control Plane）
 └─ 控制 session：用户对话 + 任务规划（DRAFT → RUNNING → INTERRUPT/COMPLETED）
    └─ LongRunningController：工具调用的执行环境

执行层（Execution Plane）
 └─ LongRunningLauncher：系统级调度器（非工具）
    └─ for 循环：起 N 个 Worker 周期
       └─ LongRunningWorkerRunner：每次起一个独立 Worker session
          └─ Worker Agent 跑完整 ReAct 循环：读 task store → 做工作 → 调 worker_report
```

**关键：Launcher 不是工具，不是模型调用的。** 它是 `YIELD_TO_RUNTIME` 机制的下游——模型调用某个 long-running 工具（如 `longrun_state_transition`），工具返回 `TurnControl.YIELD_TO_RUNTIME`，QueryEngine 把控制权交还给运行时，运行时起 Launcher。从这一刻开始模型就不再参与调度，纯系统级循环。

---

## 三、动态预算公式（必须背下来，L379-383）

```java
private int allowedWorkerCycles(LongRunningTaskStore store, String taskId, int hardLimit) {
    int featureCount = store.readFeatureList(taskId).size();
    int issueCount = store.readKnownIssues(taskId).size();
    return Math.min(hardLimit, Math.max(1, featureCount + issueCount));
}
```

`min(hardLimit, max(1, featureCount + issueCount))`

含义：**任务越复杂（feature 越多、issue 越多），允许跑的 Worker 周期越多；最少给 1 个周期；不超过调用方传入的硬上限。**

为什么不用固定轮次？任务规模动态变化——Worker 完成一个 feature 后可能发现新 issue，下一次 allowedCycles 计算就会不同。固定轮次要么太保守（任务做一半就停）要么太激进（简单任务浪费）；按 feature+issue 计算是"让任务进度驱动调度预算"。

**一个容易被追问的位置细节**：`allowedWorkerCycles` 是在 for 循环**内部**调用的（L132），不是循环前算一次：

```java
for (int i = 0; i < maxWorkers; i++) {
    ...
    int allowedCycles = allowedWorkerCycles(store, taskId, maxWorkers);   // 每轮重算
    if (i >= allowedCycles) { /* 预算耗尽 → WORKER_CYCLE_BUDGET_EXHAUSTED → INTERRUPT */ }
```

这才是"动态"的真正含义：Worker 在第 3 周期发现了 2 个新 issue，第 4 周期开始前预算立刻变大，循环得以继续。如果循环前算一次，那就只是"可变的固定值"，不是动态预算。

---

## 四、Worker 的有界工作原则

每次 Launcher 起的 Worker 拿到的 prompt（`LongRunningWorkerRunner` L147-148）:

> "Execute **exactly one long-running worker cycle** for task. Rebuild context from the task store and workspace, choose **one bounded work item**, update progress, verify your work, and report the outcome."

**"one bounded work item"** 是关键约束——Worker 不能无限干活，必须在有限时间内完成一件事并汇报。为什么？

1. **可中断性**：每个 Worker 周期结束时是自然的中断点，用户可以在任意周期之间暂停任务。
2. **进度可观测**：每个 Worker 跑完都调 `worker_report`，Launcher 和用户能持续看到进展。
3. **故障隔离**：Worker 崩了只影响当前周期，Launcher 根据报告决定是否继续，不会因为一次失败毁掉整个任务。

Worker 汇报的状态有四种：`PROGRESS_MADE`（继续下一周期）、`TASK_COMPLETED`（任务完成）、`BLOCKED`（遇到障碍，可能需要用户干预）、`FAILED`（这一轮彻底失败）。

---

## 五、BLOCKED 的处理：次数 × 严重性的二维决策（L276-313）

⚠️ 这里**不是**"试 3 次就放弃"的一维策略。真实逻辑在 `LongRunningTaskRepository.recordIssueFixAttempt`（L413-423）：

```java
if (attempts >= threshold) {              // threshold = ISSUE_FIX_ATTEMPT_THRESHOLD = 3
    if (isBlockerSeverity(issue.severity())) {
        outcome = ESCALATED;              // 升级用户
    } else {
        status = "deferred";
        outcome = DEFERRED;               // 标记 deferred，任务继续
    }
} else {
    outcome = RETRY;                      // 未到阈值，下轮再试
}
```

Launcher 侧只认 `RETRY` 和 `DEFERRED` 两种结果 → `continue` 下一周期；其余落到 `returnTaskToInterrupt`：

| attempts | severity | outcome | Launcher 行为 |
|---|---|---|---|
| < 3 | 任意 | `RETRY` | `continue`，下周期重试 |
| ≥ 3 | 普通 | `DEFERRED` | **`continue`，issue 标记 deferred，任务继续推进** |
| ≥ 3 | **blocker** | `ESCALATED` | 落到停止分支 → INTERRUPT，升级用户 |
| — | issue 未知/已 resolved | 抛异常，`outcome` 保持 null | 落到停止分支 |

**关键：到达阈值后走哪条分支由 severity 决定，不由次数决定。** blocker 级别是唯一**不会**被自动延后的——普通 issue 试满 3 次会被 park 掉然后任务继续跑。

源码注释说得很直白（L277-280）：

> "below threshold retry next cycle, at threshold **auto-defer (ordinary)** and keep going. **Only blocker-severity escalation** falls through to a real stop."

**面试一句话**："BLOCKED 是个逃生阀，防止一个 issue 卡死整个任务。没到 3 次就重试；到了 3 次分严重级别——普通 issue 自动 defer、任务继续跑别的，只有 blocker 才升级给用户。设计意图是普通问题不该阻塞整体进度，先绕过去记账；blocker 顾名思义绕不过去，必须让用户决断。"

> 💡 别说成"一个 blocker issue 最多试 3 次就挂起任务"——正好说反了，blocker 恰恰是唯一不会被自动延后的那一类。

---

## 六、中断恢复：PROCESS_RESTARTED 机制（LongRunningSessionRecovery）

这是这个模块最精妙的设计点。考虑场景：任务 RUNNING 状态下进程被 kill（断电、OOM、用户强杀）。重启后怎么办？

```java
// reconcileStage 里的 RUNNING 分支
case "RUNNING" -> recoverRunningTask(session, store, task.id());

// recoverRunningTask
try (LongRunningTaskLease ignored = store.acquireExecutionLease(taskId)) {
    store.applyLifecycleEvent(taskId,
        LongRunningLifecycleEvent.recovery(Trigger.PROCESS_RESTARTED));
    session.setLongRunningStage(LongRunningStage.INTERRUPT);
} catch (LongRunningTaskLeaseUnavailableException e) {
    // 有人在跑，不是孤儿——什么也不做
    session.setLongRunningStage(LongRunningStage.INTERRUPT);
    session.setLongRunningReason(Trigger.ALREADY_RUNNING_ELSEWHERE.wire());
}
```

**流程**：恢复时如果 task store 显示 RUNNING → 先尝试获取执行租约（`acquireExecutionLease`）：

- **能拿到租约**（真正没人在跑）→ 发 `PROCESS_RESTARTED` 触发器把任务从 RUNNING 迁移到 INTERRUPT → session 置为 INTERRUPT，用户可以选择重新恢复。
- **拿不到租约**（有进程在跑，不是孤儿）→ 直接把 session 标为 INTERRUPT + `ALREADY_RUNNING_ELSEWHERE`，不干扰正在运行的 launcher。

**为什么不直接复活成 RUNNING？** 进程死亡时 Worker 可能已经改了文件但没有汇报，task store 的进度可能不一致。先进 INTERRUPT，让用户确认状态后再决定是否继续，比盲目重启安全。

**兜底不变量（值得单独说）**：`recover()` 最外层还包了一层 catch（L46-49）——task store 读失败、元数据损坏、任何 RuntimeException，一律落到 `INTERRUPT + RECOVERY_FAILED`：

```java
} catch (RuntimeException ignored) {
    session.setLongRunningStage(LongRunningStage.INTERRUPT);
    session.setLongRunningReason(Trigger.RECOVERY_FAILED.wire());
}
```

加上 `reconcileStage` 对 RUNNING 的特殊处理，结果是：**没有任何一条恢复路径能让会话停留在 RUNNING 状态**。这就是类注释里那句 "stale RUNNING sessions do not resurrect dead launchers" 的实现——不是靠某个分支写对了，而是靠"所有异常路径都收敛到 INTERRUPT"这个不变量兜住的。

---

## 七、任务状态机（面试被问到时的简化版）

```
DRAFT → RUNNING → COMPLETED
              ↓ (任何中断原因)
           INTERRUPT → RUNNING（恢复）
              ↓
          CANCELLED / FAILED
```

中断原因有十几种（用户取消、Worker 崩溃、API 错误、模型截断、进程重启……），全部收敛到 INTERRUPT 状态，不同 `InterruptCause` 标记了原因。用户恢复时触发 `RESUME_AFTER_INTERRUPT`，Launcher 重新启动。

---

## 八、面试官追问预案

**Q1：控制/执行平面分离是什么意思，为什么要分离？**
参考：控制 session 存用户的对话历史和任务规划，不随每个 Worker 周期失效；执行层的每个 Worker 是无状态的独立 session，跑完即丢。分离的原因：任务可能跑几十个 Worker 周期，如果每个 Worker 的对话都堆在一个 session 里，上下文会迅速溢出；而且控制层和执行层的角色完全不同（一个是"记录员 + 决策者"，一个是"干活的"），混在一起耦合会很高。

**Q2：动态预算公式的 featureCount + issueCount 是什么意思？**
参考：task store 里维护了 feature 列表（待实现的功能）和 issue 列表（发现的问题）。这两个数字反映了任务当前的已知工作量——特性越多、问题越多，允许跑的 Worker 周期越多。动态计算意味着：Worker 发现新问题后，下一次计算预算时自动增加；任务做得越多，预算随之调整，不需要用户重新估算。

**Q3：进程被 kill 了，任务数据会丢失吗？**
参考：不会。task store 是文件系统持久化，每个 Worker 周期结束调 `worker_report` 写入最新进度；Launcher 的每个关键决策也写入事件日志。进程重启后 `LongRunningSessionRecovery` 读 task store 恢复状态，RUNNING 状态的孤儿任务被迁移到 INTERRUPT，用户可以选择继续——数据在，只是执行暂停了。
补充加分：恢复路径有个强不变量——**没有任何路径能让会话停在 RUNNING**。RUNNING 分支走租约判定（能拿到 = 孤儿，迁 INTERRUPT；拿不到 = 别的进程在跑，标 `ALREADY_RUNNING_ELSEWHERE`），而 `recover()` 最外层的 catch 把所有异常（store 读失败、元数据损坏）也收敛到 `INTERRUPT + RECOVERY_FAILED`。死 launcher 复活是靠这个不变量兜住的，不是靠某个分支恰好写对。

**Q3.5：一个 issue 反复修不好，会卡死整个任务吗？**
参考：不会，但处理方式取决于严重级别——这是次数 × severity 的二维决策。未到 3 次尝试就重试；到 3 次后，**普通 issue 自动标记 deferred，任务继续推进其他工作**；**只有 blocker 级别才升级给用户**并把任务置为 INTERRUPT。设计意图：普通问题不该阻塞整体进度，先绕过去记账；blocker 绕不过去，必须让用户决断。（注意别说成"blocker 最多试 3 次就挂起"——blocker 恰恰是唯一不会被自动 defer 的那类。）

**Q4：Worker 崩溃了（没有调 worker_report），系统怎么处理？**
参考：`LongRunningLauncher` 里处理 worker_run 抛出 RuntimeException 的分支（L175-212）：崩溃原因分两类——被中断（`isInterrupted()`）和真正崩了。真正崩了 → 记事件 `worker_finished (crashed)` → `returnTaskToInterrupt` 把任务置为 INTERRUPT + `WORKER_CRASH` → 返回 `LaunchStatus.FAILED`。用户看到任务停了，可以查事件日志了解原因，然后决定是否恢复。

**Q5：为什么 Launcher 不是工具，不能被模型直接调用？**
参考：模型调用工具是在 QueryEngine 的 ReAct 循环内，受 maxIterations 和上下文大小约束；Launcher 要独立循环起几十个 Worker，这个调度逻辑不应该暴露给模型决策——让模型控制调度意味着模型可以用工具调出无限的工作，成本和安全都无法保证。`YIELD_TO_RUNTIME` 是刻意设计的"控制权交接"边界：模型说"我要开始长期任务"，系统接管后续调度，模型退出循环。

---

## 九、自查清单

- [ ] 我能画出三层架构图（控制层 / Launcher / Worker）
- [ ] 我能背出动态预算公式，并说明它在 for 循环**内部**每轮重算
- [ ] 我能解释为什么 Worker 要做"一件有界工作"
- [ ] 我能讲清 BLOCKED 是次数 × severity 的二维决策（普通 defer 继续 / blocker 才升级）
- [ ] 我能讲清 PROCESS_RESTARTED 的恢复逻辑（租约 + 两种结果 + 异常兜底）
- [ ] 我能解释任务状态机的核心路径（DRAFT→RUNNING→INTERRUPT→继续/放弃）
- [ ] 我能回答"Launcher 为什么不是工具"

读完源码（重点读 `LongRunningLauncher` 的 for 循环部分和 `LongRunningSessionRecovery`）回来说"06 读完了"。
