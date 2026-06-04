package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningControllerTest {

    @TempDir
    Path tempDir;

    private ConversationSession sessionWithTask(String taskId, LongRunningStage stage) {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(taskId, "Test Task", stage.name(), null, "test-session", "test plan"));

        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(stage);
        session.setLongRunningTaskId(taskId);
        session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());
        return session;
    }

    @Test
    void requestTransitionRecordsPendingRequestWithoutChangingStage() {
        ConversationSession session = sessionWithTask("task-request", LongRunningStage.DRAFT);

        new LongRunningController().requestTransition(
                session,
                LongRunningStage.RUNNING,
                "user_confirmed_start",
                "start",
                null,
                "test");

        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertTrue(session.pendingLongRunningTransitionRequest().isPresent());
    }

    @Test
    void applyPendingStartMovesToRunning() {
        ConversationSession session = sessionWithTask("task-start", LongRunningStage.DRAFT);
        LongRunningController controller = new LongRunningController();

        controller.requestTransition(session, LongRunningStage.RUNNING,
                "user_confirmed_start", "approved", null, "test");
        controller.applyPendingRequest(session, "user", null);

        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertFalse(session.pendingLongRunningTransitionRequest().isPresent());
        assertEquals("RUNNING", new LongRunningTaskStore(tempDir).loadTask("task-start").status());
    }

    @Test
    void rejectPendingRequestKeepsCurrentStage() {
        ConversationSession session = sessionWithTask("task-reject", LongRunningStage.DRAFT);
        LongRunningController controller = new LongRunningController();

        controller.requestTransition(session, LongRunningStage.RUNNING,
                "user_confirmed_start", "approved", null, "test");
        controller.rejectPendingRequest(session, "user");

        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertFalse(session.pendingLongRunningTransitionRequest().isPresent());
    }

    @Test
    void applyPendingCancelMovesToDone() {
        ConversationSession session = sessionWithTask("task-cancel", LongRunningStage.DRAFT);
        LongRunningController controller = new LongRunningController();

        controller.requestTransition(session, LongRunningStage.DONE,
                "user_requested_cancel", "cancel", null, "test");
        controller.applyPendingRequest(session, "user", null);

        assertEquals(LongRunningStage.DONE, session.longRunningStage());
        assertEquals("DONE", new LongRunningTaskStore(tempDir).loadTask("task-cancel").status());
    }

    @Test
    void requestCreatesDraftTaskShellWhenMissing() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        new LongRunningController().requestTransition(session, LongRunningStage.RUNNING,
                "user_confirmed_start", "new task", null, "test");

        assertTrue(session.longRunningTaskId() != null && !session.longRunningTaskId().isBlank());
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertTrue(session.pendingLongRunningTransitionRequest().isPresent());
    }
}
