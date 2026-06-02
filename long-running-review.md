# Long-Running Mode 架构审查报告

## 功能概述

Long-Running Mode 是一个面向长期开发任务的工作流模式,支持:
- 多阶段状态机管理 (WAITING_FOR_TASK → PLANNING → WAITING_FOR_APPROVAL → INITIALIZING → EXECUTING → COMPLETED/CANCELLED)
- 持久化任务存储 (`.mada/long-running/{taskId}/`)
- Feature list 跟踪与验证
- Known issues 记录与解决
- Progress 日志追加

## 设计链路

```
用户输入 → Repl → ModeRouter → LongRunningModeHandler 
                                    ↓
                               状态机转换 + TurnExecutor
                                    ↓
                          QueryEngine (正常 turn 流程)
                                    ↓
                     LongRunTaskUpdateTool / LongRunStageUpdateTool
                                    ↓
                          LongRunningTaskStore (持久化)
```

---

## 🔴 发现的设计缺陷与逻辑问题

### 1. **严重并发安全问题: 状态机转换的竞态条件**

**位置**: `LongRunningModeHandler.java:101-126`

```java
private void applyStageUpdate(ConversationSession session, String expandedInput) {
    var update = session.lastLongRunningStageUpdate().orElse(null);
    if (update == null || update.confidence() != ConversationSession.LongRunningConfidence.HIGH) {
        return;
    }
    // 🔴 TOCTOU: session.longRunningStage() 可能在下面的 switch 执行前被其他线程修改
    if (session.longRunningStage() != update.stage()) {
        session.clearLongRunningStageUpdate();
        return;
    }
    switch (update.intent()) {
        case FINALIZE_PLAN -> {
            // 🔴 再次检查但不是原子操作
            if (session.longRunningStage() == LongRunningStage.PLANNING) {
                session.setLongRunningPlanSummary(update.summary());
                session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
            }
        }
        // ...
    }
}
```

**问题**:
- `session.longRunningStage()` 检查与 `setLongRunningStage()` 不是原子的
- 如果两个线程同时执行 `applyStageUpdate()`,可能导致状态跳转异常
- `ConversationSession` 的注释明确说 "Concurrent writes from multiple threads are not supported"

**影响**: 如果引入多线程处理 (如后台任务或并发 tool),状态机可能出现不一致。

**修复建议**:
```java
// 选项 1: 在 ConversationSession 中提供原子 CAS 操作
public boolean transitionStageIfMatch(LongRunningStage expected, LongRunningStage next) {
    synchronized (this) {
        if (this.longRunningStage == expected) {
            this.longRunningStage = next;
            return true;
        }
        return false;
    }
}

// 选项 2: 在 Handler 层面加锁
private synchronized void applyStageUpdate(...) { ... }
```

---

### 2. **状态机转换逻辑不一致: INITIALIZING 阶段的双重处理**

**位置**: `LongRunningModeHandler.java:58-82`

```java
public ModeExecution handle(String line, ConversationSession session) {
    // ...
    LongRunningStage stage = stage(session);
    if (stage == LongRunningStage.WAITING_FOR_TASK) {
        session.setLongRunningStage(LongRunningStage.PLANNING);
        // ...
        stage = LongRunningStage.PLANNING;
    }
    // 🔴 问题 1: 这里处理 INITIALIZING
    if (stage == LongRunningStage.INITIALIZING) {
        initializeTask(session, expanded);
        stage = LongRunningStage.EXECUTING;
    }

    ModeExecution execution = switch (stage) {
        case PLANNING, WAITING_FOR_APPROVAL -> runConversationalTurn(session, expanded);
        case EXECUTING -> runExecutingTurn(session, expanded);
        // ...
        // 🔴 问题 2: 但这里又说 INITIALIZING 不应该出现
        case WAITING_FOR_TASK, INITIALIZING ->
                throw new IllegalStateException("Unexpected long-running stage after preflight: " + stage);
    };
    // 🔴 问题 3: applyStageUpdate 里又调用一次 initializeTask
    return new ModeExecution(execution.handle(), () -> applyStageUpdate(session, expanded));
}
```

**问题**:
- `INITIALIZING` 在 `handle()` 开头就被转换为 `EXECUTING`,但 switch 分支又抛异常
- `applyStageUpdate()` 里的 `APPROVE_EXECUTION` 分支会再次调用 `initializeTask()`
- 这导致 `initializeTask()` 可能被调用两次,第二次调用时 `taskId` 已存在会走 `validateTaskDirectory()` 分支

**影响**: 
- 代码逻辑混乱,难以追踪状态转换路径
- 如果第一次 `initializeTask()` 失败但未抛异常,可能导致不一致状态

**修复建议**:
- 明确转换路径: `WAITING_FOR_APPROVAL` → (用户确认) → `INITIALIZING` → (初始化完成) → `EXECUTING`
- `handle()` 开头不处理 `INITIALIZING`,让 `applyStageUpdate()` 统一管理
- 或者删除 `INITIALIZING` 阶段,直接从 `WAITING_FOR_APPROVAL` → `EXECUTING`

---

### 3. **文件系统权限规则的不完整保护**

**位置**: `LongRunningTaskStatePermissionRule.java:36-39`

```java
if ("bash".equals(tool.name())
        && FilesystemScope.commandMayMutateProtectedLongRunningTaskState(
                input.path("command").asText(""), workingDir)) {
    return Optional.of(deny());
}
```

**问题**:
- 只检查了 `bash` 工具,但没有拦截其他可能修改文件的工具
- `FilesystemScope.commandMayMutateProtectedLongRunningTaskState()` 方法在代码中看不到完整实现
- 如果有类似 `exec_command` 或 `run_script` 的工具,可以绕过保护

**影响**: 模型可能通过其他工具绕过 `longrun_task_update`,直接修改 `task.json` 等文件。

**修复建议**:
```java
// 通用检查: 任何可能执行命令的工具
if (tool.canExecuteCommands() && 
    FilesystemScope.commandMayMutateProtectedLongRunningTaskState(...)) {
    return Optional.of(deny());
}
```

---

### 4. **Task ID 冲突处理的无限重试风险**

**位置**: `LongRunningModeHandler.java:148-174`

```java
private LongRunningTaskMetadata createTaskWithFreshId(...) {
    for (int attempt = 0; attempt < MAX_TASK_ID_ATTEMPTS; attempt++) {
        String taskId = taskIdGenerator.newTaskId(attempt);
        try {
            return store.createTask(new CreateTaskRequest(...));
        } catch (madacode.longrunning.LongRunningTaskStoreException exception) {
            // 🔴 问题: 只检查消息文本,不区分异常类型
            if (!exception.getMessage().contains("already exists")) {
                throw exception;
            }
        }
    }
    // 🔴 Fallback 使用 UUID,但不检查是否仍冲突
    String fallbackId = "task-" + TASK_ID_TIME.format(Instant.now()) + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    return store.createTask(new CreateTaskRequest(fallbackId, ...));
}
```

**问题**:
- 依赖异常消息文本判断类型,脆弱且不可靠
- Fallback UUID 仍可能冲突 (极低概率但理论存在)
- 如果 `createTask()` 因其他原因失败 (如磁盘满、权限问题),重试无意义

**修复建议**:
```java
// 1. LongRunningTaskStoreException 添加子类型
public class TaskAlreadyExistsException extends LongRunningTaskStoreException { ... }

// 2. 改进重试逻辑
for (int attempt = 0; attempt < MAX_TASK_ID_ATTEMPTS; attempt++) {
    try {
        return store.createTask(...);
    } catch (TaskAlreadyExistsException e) {
        // 继续重试
    }
    // 其他异常直接抛出
}
```

---

### 5. **Feature 依赖关系未验证**

**位置**: `LongRunningTaskStore.java:317-334`

```java
private List<FeatureItem> validateFeatureList(List<FeatureItem> features, boolean allowPassedFeatures) {
    // ...
    for (FeatureItem feature : validated) {
        // ...
        ensureListItemsPresent(feature.dependsOn(), "feature.dependsOn");
        // 🔴 问题: 只检查非空,不检查依赖的 feature 是否存在
        if (!ids.add(feature.id())) {
            throw new LongRunningTaskStoreException("Duplicate feature id: " + feature.id());
        }
        // ...
    }
    return validated;
}
```

**问题**:
- `feature.dependsOn()` 列表可能包含不存在的 feature ID
- 可能形成循环依赖 (A → B → A)
- 如果依赖的 feature 被删除,没有级联检查

**修复建议**:
```java
// 验证依赖存在且无循环
Set<String> ids = new LinkedHashSet<>();
for (FeatureItem feature : validated) {
    ids.add(feature.id());
}
for (FeatureItem feature : validated) {
    for (String dep : feature.dependsOn()) {
        if (!ids.contains(dep)) {
            throw new LongRunningTaskStoreException(
                "Feature " + feature.id() + " depends on unknown feature: " + dep);
        }
    }
}
// 循环依赖检查 (拓扑排序或 DFS)
detectCycles(validated);
```

---

### 6. **Known Issue 的状态转换不完整**

**位置**: `LongRunningTaskStore.java:27` 和 `LongRunTaskUpdateTool.java:86-89`

```java
// LongRunningTaskStore.java
private static final Set<String> ALLOWED_ISSUE_STATUSES = 
    Set.of("open", "resolved", "blocked");

// LongRunTaskUpdateTool.java (record_issue)
String status = input.status() == null || input.status().isBlank()
        ? "open"
        : input.status().strip().toLowerCase(Locale.ROOT);
```

**问题**:
- 允许创建 `blocked` 状态的 issue,但没有提供从 `blocked` → `open` 的转换方法
- `markIssueResolved()` 只能转换到 `resolved`,无法解除 `blocked`
- `markFeaturePassed()` 检查 "open or blocked" 都算 active,但没有办法 unblock

**修复建议**:
```java
// 添加状态转换方法
public KnownIssue updateIssueStatus(String taskId, String issueId, String newStatus) {
    // 验证状态转换合法性
    // blocked → open / resolved
    // open → blocked / resolved
}
```

---

### 7. **原子写入的临时文件清理不彻底**

**位置**: `LongRunningTaskStore.java:569-575`

```java
private void moveIntoPlace(Path tempFile, Path target) throws IOException {
    try {
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
        // 🔴 问题: fallback 不是原子的,可能留下不完整的文件
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
```

**问题**:
- 如果非原子 move 在中途崩溃,目标文件可能损坏
- Fallback 路径没有 `finally` 清理 tempFile

**修复建议**:
```java
private void moveIntoPlace(Path tempFile, Path target) throws IOException {
    try {
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
        // 记录警告: 原子操作不可用
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            // 确保 tempFile 被清理
            Files.deleteIfExists(tempFile);
            throw moveFailure;
        }
    }
}
```

---

### 8. **LongRunningStage.allowsIntent() 的不完整性**

**位置**: `LongRunningStage.java:15-23`

```java
public boolean allowsIntent(ConversationSession.LongRunningStageUpdateIntent intent) {
    return switch (this) {
        case PLANNING -> EnumSet.of(
                ConversationSession.LongRunningStageUpdateIntent.FINALIZE_PLAN).contains(intent);
        case WAITING_FOR_APPROVAL -> EnumSet.of(
                ConversationSession.LongRunningStageUpdateIntent.APPROVE_EXECUTION).contains(intent);
        // 🔴 问题: EXECUTING 阶段不允许 COMPLETE,但实际应该允许
        case WAITING_FOR_TASK, INITIALIZING, EXECUTING, COMPLETED, CANCELLED -> false;
    };
}
```

**问题**:
- `EXECUTING` 阶段应该允许 `COMPLETE` 和 `CANCEL` intent
- 但当前实现返回 `false`,导致无法通过工具标记完成
- 必须通过外部逻辑强制转换状态

**修复建议**:
```java
case EXECUTING -> EnumSet.of(
        ConversationSession.LongRunningStageUpdateIntent.COMPLETE,
        ConversationSession.LongRunningStageUpdateIntent.CANCEL).contains(intent);
```

---

## ⚠️ 潜在改进点

### 9. **缺少 Task 的暂停/恢复机制**

当前状态机没有 `PAUSED` 状态,如果用户需要中断长期任务稍后继续,只能保持在 `EXECUTING` 或强制 `CANCELLED`。

### 10. **Progress 日志无结构化查询能力**

`progress.txt` 是纯文本追加,难以:
- 按时间范围过滤
- 按关键词搜索
- 提取统计信息

建议改为 JSONL 格式或提供独立的查询 API。

### 11. **缺少 Task 间的关联与父子关系**

如果一个大任务需要拆分为多个子任务,当前设计无法表达这种层级关系。

---

## 测试覆盖缺口

1. **并发场景**: 缺少多线程同时修改 `ConversationSession` 状态的测试
2. **边界条件**: taskId 长度为 128 字符时的处理
3. **文件系统异常**: 磁盘满、权限不足时的错误恢复
4. **循环依赖**: Feature 依赖形成环时的检测

---

## 总结

### 需要立即修复的严重问题:
1. ✅ **状态机并发安全** (问题 #1)
2. ✅ **INITIALIZING 双重处理** (问题 #2)
3. ✅ **Feature 依赖验证** (问题 #5)
4. ✅ **EXECUTING 阶段的 intent 权限** (问题 #8)

### 建议优化:
- 问题 #3: 完善权限规则
- 问题 #4: 改进异常类型系统
- 问题 #6: 增强 issue 状态转换
- 问题 #7: 加固原子写入 fallback

整体设计思路清晰,模块职责分离良好,但在并发安全、状态机一致性和边界条件处理上还有改进空间。
