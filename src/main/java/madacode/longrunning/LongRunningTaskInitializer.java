package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Harness-owned initialization component for long-running tasks.
 *
 * <p>This class is the <em>single authority</em> for creating new tasks and
 * repairing partially-initialized session state. It ensures that planning has
 * durable state as soon as the user provides the long-running request, and
 * that approval initializes durable task state before launcher/worker handoff.
 *
 * <p>Neither the model nor the prompt should ever see initialization logic —
 * it is a code-level harness invariant enforced before every execution turn.
 */
public final class LongRunningTaskInitializer {

    private static final DateTimeFormatter TASK_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int MAX_TASK_ID_ATTEMPTS = 10;

    private final LongRunningTaskStore store;
    private final TaskIdGenerator taskIdGenerator;

    public LongRunningTaskInitializer(LongRunningTaskStore store, TaskIdGenerator taskIdGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator");
    }

    /**
     * Ensures the session has a durable planning task.
     *
     * <p>This is intentionally called before the first DRAFT model turn so
     * logs, task metadata, and workspace checkpoint files exist even if the
     * model later asks questions or the process is interrupted.
     */
    public LongRunningTaskContext ensurePlanningTask(ConversationSession session, String expandedInput) {
        Objects.requireNonNull(session, "session");
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            throw new IllegalStateException(
                    "ensurePlanningTask requires LONG_RUNNING mode, got " + session.workflowMode());
        }
        if (session.longRunningTaskDirectory() != null && session.longRunningTaskId() == null) {
            session.setLongRunningTaskDirectory(null);
        }
        if (session.longRunningTaskId() != null) {
            return restorePlanningTask(session);
        }
        return createNewTask(session, expandedInput, "DRAFT", LongRunningStage.DRAFT);
    }

    /**
     * Ensures the session has initialized durable task state for execution handoff.
     *
     * <ul>
     *   <li>If the session already has a {@code taskId}, validates the on-disk
     *       task directory and repairs the session's directory/title fields.</li>
     *   <li>If {@code taskId} is null, creates a fresh task with a unique id,
     *       writes all default files, and appends an initial progress entry.</li>
     *   <li>If {@code taskId} is null but {@code taskDirectory} is non-null
     *       (stale partial state), clears the directory first.</li>
     * </ul>
     *
     * @param session      the session to initialize (must be LONG_RUNNING mode)
     * @param expandedInput the expanded user input for the initial progress entry
     * @return the initialised task context
     * @throws IllegalStateException       if session is not in LONG_RUNNING mode
     * @throws LongRunningTaskStoreException if the on-disk store is missing or corrupt
     */
    public LongRunningTaskContext ensureExecutionTask(ConversationSession session, String expandedInput) {
        Objects.requireNonNull(session, "session");
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            throw new IllegalStateException(
                    "ensureExecutionTask requires LONG_RUNNING mode, got " + session.workflowMode());
        }
        session.setPlanMode(false);

        // Stale partial state: taskDirectory without taskId — clear and recreate
        if (session.longRunningTaskId() == null && session.longRunningTaskDirectory() != null) {
            session.setLongRunningTaskDirectory(null);
        }

        if (session.longRunningTaskId() != null) {
            LongRunningTaskContext context = restoreOrInitializeExecutionTask(session, expandedInput);
            persistApprovedPlan(session);
            return context;
        }

        LongRunningTaskContext context = createNewTask(session, expandedInput, "DRAFT", LongRunningStage.DRAFT);
        context = restoreOrInitializeExecutionTask(session, expandedInput);
        persistApprovedPlan(session);
        return context;
    }

    private LongRunningTaskContext restorePlanningTask(ConversationSession session) {
        String taskId = session.longRunningTaskId();
        String previousDirectory = session.longRunningTaskDirectory();
        Path dir = store.validateTaskDirectory(taskId);
        LongRunningTaskMetadata meta = store.loadTask(taskId);
        if (!"DRAFT".equals(meta.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " is not a draft task: status="
                            + meta.status() + ", stage=" + meta.stage());
        }
        session.setLongRunningTaskDirectory(dir.toString());
        if (session.longRunningTaskTitle() == null) {
            session.setLongRunningTaskTitle(meta.title());
        }
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningReason(meta.reason());
        if (previousDirectory == null || !previousDirectory.equals(dir.toString())) {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    "task_context_repaired",
                    taskId,
                    session.sessionId(),
                    LongRunningStage.DRAFT.name(),
                    null,
                    true,
                    "Session task directory repaired from validated task store.",
                    Map.of(
                            "previousDirectory", previousDirectory == null ? "" : previousDirectory,
                            "taskDirectory", dir.toString())));
        }
        return new LongRunningTaskContext(meta.id(), dir, meta);
    }

    private LongRunningTaskContext restoreOrInitializeExecutionTask(
            ConversationSession session,
            String expandedInput) {
        String taskId = session.longRunningTaskId();
        String previousDirectory = session.longRunningTaskDirectory();
        Path dir = store.validateTaskDirectory(taskId);
        LongRunningTaskMetadata meta = store.loadTask(taskId);
        if ("DRAFT".equals(meta.status())) {
            meta = store.markTaskExecuting(taskId);
            store.appendProgress(taskId, initializedProgressEntry(session, expandedInput));
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    "task_execution_initialized",
                    taskId,
                    session.sessionId(),
                    LongRunningStage.RUNNING.name(),
                    null,
                    true,
                    "Long-running task entered RUNNING; awaiting launcher/worker.",
                    Map.of("taskDirectory", dir.toString())));
        } else if (!"RUNNING".equals(meta.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " is not ready for execution handoff: status="
                            + meta.status() + ", stage=" + meta.stage());
        }
        session.setLongRunningTaskDirectory(dir.toString());
        if (session.longRunningTaskTitle() == null) {
            session.setLongRunningTaskTitle(meta.title());
        }
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningReason(meta.reason());
        if (previousDirectory == null || !previousDirectory.equals(dir.toString())) {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    "task_context_repaired",
                    taskId,
                    session.sessionId(),
                    LongRunningStage.RUNNING.name(),
                    null,
                    true,
                    "Session task directory repaired from validated task store.",
                    Map.of(
                            "previousDirectory", previousDirectory == null ? "" : previousDirectory,
                            "taskDirectory", dir.toString())));
        }
        return new LongRunningTaskContext(meta.id(), dir, meta);
    }

    private LongRunningTaskContext createNewTask(
            ConversationSession session,
            String expandedInput,
            String status,
            LongRunningStage stage) {
        LongRunningTaskMetadata meta = createTaskWithFreshId(session, status, stage);
        Path dir = store.validateTaskDirectory(meta.id());
        session.setLongRunningTaskId(meta.id());
        session.setLongRunningTaskDirectory(dir.toString());
        session.setLongRunningStage(stage);
        LongRunningWorkspaceCheckpoint checkpoint =
                LongRunningWorkspaceCheckpoint.capture(session.workingDirectory());
        store.writeCheckpoint(meta.id(), checkpoint);
        store.appendProgress(meta.id(), initialProgressEntry(session, expandedInput));
        store.appendEvent(meta.id(), LongRunningTaskEvent.of(
                "task_created",
                meta.id(),
                session.sessionId(),
                stage.name(),
                null,
                true,
                "Long-running task initialized.",
                Map.of(
                        "title", meta.title(),
                        "status", status,
                        "taskDirectory", dir.toString())));
        store.appendEvent(meta.id(), LongRunningTaskEvent.of(
                "workspace_checkpoint_created",
                meta.id(),
                session.sessionId(),
                stage.name(),
                null,
                true,
                "Workspace checkpoint captured for long-running task.",
                Map.of(
                        "gitRepository", String.valueOf(checkpoint.gitRepository()),
                        "branch", checkpoint.branch() == null ? "" : checkpoint.branch(),
                        "head", checkpoint.head() == null ? "" : checkpoint.head(),
                        "dirty", String.valueOf(checkpoint.dirty()))));
        return new LongRunningTaskContext(meta.id(), dir, meta);
    }

    private LongRunningTaskMetadata createTaskWithFreshId(
            ConversationSession session,
            String status,
            LongRunningStage stage) {
        for (int attempt = 0; attempt < MAX_TASK_ID_ATTEMPTS; attempt++) {
            String taskId = taskIdGenerator.newTaskId(attempt);
            try {
                return store.createTask(new CreateTaskRequest(
                        taskId,
                        durableTaskTitle(session),
                        status,
                        session.sessionId(),
                        stage.name()));
            } catch (LongRunningTaskStoreException exception) {
                if (!exception.getMessage().contains("already exists")) {
                    throw exception;
                }
            }
        }
        // All attempts failed due to collisions — fall back to UUID-suffixed id
        String fallbackId = "task-" + TASK_ID_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        return store.createTask(new CreateTaskRequest(
                fallbackId,
                durableTaskTitle(session),
                status,
                session.sessionId(),
                stage.name()));
    }

    private static String durableTaskTitle(ConversationSession session) {
        if (session.longRunningTaskTitle() != null) {
            return session.longRunningTaskTitle();
        }
        if (session.longRunningPlanSummary() != null) {
            return taskTitle(session.longRunningPlanSummary());
        }
        return "Long-running task";
    }

    /**
     * Derives a human-readable title from arbitrary input text. Truncates at 80 chars.
     */
    public static String taskTitle(String input) {
        String normalized = input == null ? "" : input.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "Long-running task";
        }
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    private static String initialProgressEntry(ConversationSession session, String input) {
        String stageLine = String.valueOf(session.longRunningStage());
        return """
                ## %s
                stage: %s
                session: %s
                task: %s
                initial input: %s

                """.formatted(
                Instant.now(),
                stageLine,
                session.sessionId(),
                session.longRunningTaskId(),
                input == null ? "" : input.strip());
    }

    private static String initializedProgressEntry(ConversationSession session, String input) {
        return """
                ## %s
                stage: RUNNING
                session: %s
                task: %s
                approval input: %s
                status: task entered RUNNING; awaiting launcher/worker

                """.formatted(
                Instant.now(),
                session.sessionId(),
                session.longRunningTaskId(),
                input == null ? "" : input.strip());
    }

    private void persistApprovedPlan(ConversationSession session) {
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        StringBuilder plan = new StringBuilder();
        if (session.longRunningTaskTitle() != null && !session.longRunningTaskTitle().isBlank()) {
            plan.append("# ").append(session.longRunningTaskTitle().strip()).append("\n\n");
        }
        if (session.longRunningPlanSummary() != null && !session.longRunningPlanSummary().isBlank()) {
            plan.append(session.longRunningPlanSummary().strip()).append("\n");
        }
        if (!plan.isEmpty()) {
            store.writeApprovedPlan(taskId, plan.toString());
        }
    }

    /**
     * Generates a unique task id on demand.
     *
     * <p>The default implementation produces timestamp-based ids
     * ({@code task-YYYYMMDD-HHmmss}) and appends {@code -N} on collision retries.
     */
    @FunctionalInterface
    public interface TaskIdGenerator {
        String newTaskId(int attempt);

        static String defaultNewTaskId(int attempt) {
            String base = "task-" + TASK_ID_TIME.format(Instant.now());
            return attempt == 0 ? base : base + "-" + attempt;
        }
    }
}
