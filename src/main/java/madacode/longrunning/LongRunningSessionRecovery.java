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
 * and session visibility stays aligned with the task metadata.
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
            session.setLongRunningReason(LongRunningTransitions.Trigger.RECOVERY_FAILED.wire());
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
            case "COMPLETED", "CANCELLED", "FAILED" -> setStageFromTask(session, task);
            case "RUNNING" -> recoverRunningTask(session, store, task.id());
            default -> throw new IllegalStateException("Unsupported task status: " + task.status());
        }
    }

    private static void setStageFromTask(ConversationSession session, LongRunningTaskMetadata task) {
        LongRunningStage stage = LongRunningStage.fromWire(task.status())
                .orElseThrow(() -> new IllegalStateException("Unsupported task status: " + task.status()));
        session.setLongRunningStage(stage);
        session.setLongRunningReason(task.reason());
    }

    private static void recoverRunningTask(
            ConversationSession session,
            LongRunningTaskStore store,
            String taskId) {
        try (LongRunningTaskLease ignored = store.acquireExecutionLease(taskId)) {
            LongRunningTaskMetadata interrupted = store.applyLifecycleEvent(
                    taskId,
                    LongRunningLifecycleEvent.recovery(LongRunningTransitions.Trigger.PROCESS_RESTARTED));
            session.setLongRunningStage(LongRunningStage.INTERRUPT);
            session.setLongRunningReason(interrupted.reason());
        } catch (LongRunningTaskLeaseUnavailableException exception) {
            session.setLongRunningStage(LongRunningStage.INTERRUPT);
            session.setLongRunningReason(LongRunningTransitions.Trigger.ALREADY_RUNNING_ELSEWHERE.wire());
        }
    }
}
