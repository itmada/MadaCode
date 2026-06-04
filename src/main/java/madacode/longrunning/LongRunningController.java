package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Service layer for long-running task stage transitions.
 * Called directly by slash commands and the launcher — not by model tool calls.
 */
public final class LongRunningController {

    private final TaskStoreFactory taskStoreFactory;
    private final LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator;

    public LongRunningController() {
        this(LongRunningTaskStore::new, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    public LongRunningController(TaskStoreFactory taskStoreFactory) {
        this(taskStoreFactory, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    public LongRunningController(TaskStoreFactory taskStoreFactory,
                                  LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator) {
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator");
    }

    // ---- Stage transition methods called by slash commands ----

    public void finalizePlan(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        LongRunningStage stage = session.longRunningStage();
        if (stage != LongRunningStage.DRAFT) {
            throw new IllegalStateException(
                    "Cannot finalize plan: session is in stage " + stage);
        }
        String taskId = requireActiveTask(session);
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        appendEvent(store, taskId, "plan_ready", "FINALIZE_PLAN",
                true, "Plan is ready for a future RUNNING transition.", session.longRunningStage());
    }

    public void approveExecution(ConversationSession session, String expandedInput) {
        Objects.requireNonNull(session, "session");
        if (session.longRunningStage() != LongRunningStage.DRAFT) {
            throw new IllegalStateException(
                    "Cannot approve execution: session is in stage " + session.longRunningStage());
        }
        String taskId = requireActiveTask(session);
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());

        session.setLongRunningTurnAssignment(null);

        LongRunningTaskInitializer initializer = new LongRunningTaskInitializer(store, taskIdGenerator);
        initializer.ensureExecutionTask(session, expandedInput);
        appendEvent(store, taskId, "stage_transition", "APPROVE_EXECUTION",
                true, "Execution approved; control session entered RUNNING.", session.longRunningStage());
    }

    public void revisePlan(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        if (session.longRunningStage() != LongRunningStage.DRAFT) {
            throw new IllegalStateException(
                    "Cannot revise plan: session is in stage " + session.longRunningStage());
        }
        String taskId = requireActiveTask(session);
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        appendEvent(store, taskId, "stage_transition", "REVISE_PLAN",
                true, "Plan remains in DRAFT for revision.", session.longRunningStage());
    }

    public void cancelTask(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        String taskId = requireActiveTask(session);
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());

        store.cancelTask(taskId);
        session.setLongRunningStage(LongRunningStage.DONE);
        appendEvent(store, taskId, "stage_transition", "CANCEL",
                true, "Task cancelled by user.", session.longRunningStage());
    }

    // ---- Helpers ----

    private String requireActiveTask(ConversationSession session) {
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("No active long-running task on session.");
        }
        return taskId;
    }

    private static void appendEvent(LongRunningTaskStore store, String taskId,
                                     String type, String action, boolean success,
                                     String message, LongRunningStage stage) {
        try {
            store.appendEvent(taskId, new LongRunningTaskEvent(
                    Instant.now(), type, taskId, null,
                    stage != null ? stage.name() : null,
                    action, success, message, Map.of()));
        } catch (RuntimeException ignored) {
            // best-effort event logging
        }
    }

    @FunctionalInterface
    public interface TaskStoreFactory {
        LongRunningTaskStore create(Path projectDir);
    }
}
