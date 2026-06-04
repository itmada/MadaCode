package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongRunningControllerTest {

    @TempDir
    Path tempDir;

    private ConversationSession sessionWithTask(String taskId, LongRunningStage stage) {
        // Create the task on disk so the controller can access it
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(taskId, "Test Task", "planning", "test-session", stage.name()));

        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(stage);
        session.setLongRunningTaskId(taskId);
        session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());
        return session;
    }

    @Test
    void finalizePlanMovesFromPlanningToWaitingForApproval() {
        ConversationSession session = sessionWithTask("task-finalize", LongRunningStage.PLANNING);

        new LongRunningController().finalizePlan(session);

        assertEquals(LongRunningStage.WAITING_FOR_APPROVAL, session.longRunningStage());
    }

    @Test
    void finalizePlanThrowsInWrongStage() {
        ConversationSession session = sessionWithTask("task-finalize-err", LongRunningStage.EXECUTING);

        assertThrows(IllegalStateException.class,
                () -> new LongRunningController().finalizePlan(session));
    }

    @Test
    void revisePlanMovesBackToPlanning() {
        ConversationSession session = sessionWithTask("task-revise", LongRunningStage.WAITING_FOR_APPROVAL);

        new LongRunningController().revisePlan(session);

        assertEquals(LongRunningStage.PLANNING, session.longRunningStage());
    }

    @Test
    void revisePlanThrowsInWrongStage() {
        ConversationSession session = sessionWithTask("task-revise-err", LongRunningStage.PLANNING);

        assertThrows(IllegalStateException.class,
                () -> new LongRunningController().revisePlan(session));
    }

    @Test
    void cancelTaskMovesToCancelled() {
        ConversationSession session = sessionWithTask("task-cancel", LongRunningStage.PLANNING);

        new LongRunningController().cancelTask(session);

        assertEquals(LongRunningStage.CANCELLED, session.longRunningStage());
    }

    @Test
    void cancelTaskThrowsWhenNoTaskId() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        assertThrows(IllegalStateException.class,
                () -> new LongRunningController().cancelTask(session));
    }

    @Test
    void approveExecutionThrowsWhenNotInApproval() {
        ConversationSession session = sessionWithTask("task-approve-err", LongRunningStage.PLANNING);

        assertThrows(IllegalStateException.class,
                () -> new LongRunningController().approveExecution(session, ""));
    }

    @Test
    void requiresActiveTaskForTransitions() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        assertThrows(IllegalStateException.class,
                () -> new LongRunningController().finalizePlan(session));
    }
}
