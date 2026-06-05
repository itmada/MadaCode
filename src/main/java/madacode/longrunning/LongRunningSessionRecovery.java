package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

import java.util.Objects;

/**
 * Reconciles a resumed control session with its durable long-running task.
 *
 * <p>The transcript stores the controller conversation, while the task store is
 * the lifecycle authority for worker execution. Every resume path must pass
 * through this class so stale RUNNING sessions do not resurrect dead launchers
 * and DRAFT/INTERRUPT/DONE visibility stays aligned with the task metadata.
 */
public final class LongRunningSessionRecovery {

    private LongRunningSessionRecovery() {}

    public static void recover(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        if (session.workflowMode() != SessionMode.LONG_RUNNING
                || session.isLongRunningWorkerSession()) {
            return;
        }
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            if (session.longRunningStage() == null) {
                session.setLongRunningStage(LongRunningStage.DRAFT);
            }
            return;
        }

        try {
            LongRunningTaskStore store = new LongRunningTaskStore(session.workingDirectory());
            LongRunningTaskMetadata task = store.loadTask(taskId);
            session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());
            if (session.longRunningTaskTitle() == null || session.longRunningTaskTitle().isBlank()) {
                session.setLongRunningTaskTitle(task.title());
            }
            if (session.longRunningPlanSummary() == null || session.longRunningPlanSummary().isBlank()) {
                session.setLongRunningPlanSummary(task.planSummary());
            }
            reconcileStage(session, store, task);
        } catch (RuntimeException ignored) {
            session.setLongRunningStage(LongRunningStage.INTERRUPT);
            session.setLongRunningReason("recovery_failed");
        }
    }

    private static void reconcileStage(
            ConversationSession session,
            LongRunningTaskStore store,
            LongRunningTaskMetadata task) {
        switch (task.status()) {
            case "DRAFT" -> {
                session.setLongRunningStage(LongRunningStage.DRAFT);
                session.setLongRunningReason(task.reason());
            }
            case "INTERRUPT" -> {
                session.setLongRunningStage(LongRunningStage.INTERRUPT);
                session.setLongRunningReason(task.reason());
            }
            case "DONE" -> {
                session.setLongRunningStage(LongRunningStage.DONE);
                session.setLongRunningReason(task.reason());
            }
            case "RUNNING" -> recoverRunningTask(session, store, task.id());
            default -> throw new IllegalStateException("Unsupported task status: " + task.status());
        }
    }

    private static void recoverRunningTask(
            ConversationSession session,
            LongRunningTaskStore store,
            String taskId) {
        try (LongRunningTaskLease ignored = store.acquireExecutionLease(taskId)) {
            LongRunningTaskMetadata interrupted = store.markTaskInterrupted(taskId, "process_restarted");
            session.setLongRunningStage(LongRunningStage.INTERRUPT);
            session.setLongRunningReason(interrupted.reason());
        } catch (LongRunningTaskLeaseUnavailableException exception) {
            session.setLongRunningStage(LongRunningStage.INTERRUPT);
            session.setLongRunningReason("already_running_elsewhere");
        }
    }
}
