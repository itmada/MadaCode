package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private void seedFeature(String taskId) {
        new LongRunningTaskStore(tempDir).writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-a", "core", "high", "Feature A", List.of(), List.of("verify"), false)));
    }

    @Test
    void requestTransitionRecordsPendingRequestWithoutChangingStage() {
        ConversationSession session = sessionWithTask("task-request", LongRunningStage.DRAFT);
        seedFeature("task-request");

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
        seedFeature("task-start");
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
        seedFeature("task-reject");
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
    void applyPendingInterruptMovesRunningTaskToInterrupt() {
        ConversationSession session = sessionWithTask("task-interrupt", LongRunningStage.DRAFT);
        seedFeature("task-interrupt");
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.markTaskExecuting("task-interrupt");
        session.setLongRunningStage(LongRunningStage.RUNNING);
        LongRunningController controller = new LongRunningController();

        controller.requestTransition(session, LongRunningStage.INTERRUPT,
                "user_interrupted", "pause", null, "test");
        controller.applyPendingRequest(session, "user", null);

        assertEquals(LongRunningStage.INTERRUPT, session.longRunningStage());
        assertEquals("INTERRUPT", store.loadTask("task-interrupt").status());
    }

    @Test
    void applyPendingResumeMovesInterruptedTaskToRunning() {
        ConversationSession session = sessionWithTask("task-resume", LongRunningStage.DRAFT);
        seedFeature("task-resume");
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.markTaskExecuting("task-resume");
        store.markTaskInterrupted("task-resume", "user_interrupted");
        session.setLongRunningStage(LongRunningStage.INTERRUPT);
        LongRunningController controller = new LongRunningController();

        controller.requestTransition(session, LongRunningStage.RUNNING,
                "resume_after_interrupt", "resume", null, "test");
        controller.applyPendingRequest(session, "user", null);

        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store.loadTask("task-resume").status());
    }

    @Test
    void rejectsMismatchedTransitionReason() {
        ConversationSession session = sessionWithTask("task-bad-reason", LongRunningStage.DRAFT);
        seedFeature("task-bad-reason");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new LongRunningController().requestTransition(
                        session,
                        LongRunningStage.RUNNING,
                        "user_requested_cancel",
                        "wrong",
                        null,
                        "test"));

        assertTrue(ex.getMessage().contains("Invalid long-running transition reason"));
        assertFalse(session.pendingLongRunningTransitionRequest().isPresent());
    }

    @Test
    void requestCreatesDraftTaskShellWhenMissing() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        try {
            new LongRunningController().requestTransition(session, LongRunningStage.RUNNING,
                    "user_confirmed_start", "new task", null, "test");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("feature_list.json"));
        }

        assertTrue(session.longRunningTaskId() != null && !session.longRunningTaskId().isBlank());
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertFalse(session.pendingLongRunningTransitionRequest().isPresent());
    }
}
