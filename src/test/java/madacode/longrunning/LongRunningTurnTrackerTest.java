package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTurnTrackerTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
    }

    private ConversationSession executingSession(String taskId) {
        store.createTask(new CreateTaskRequest(taskId, "Test", "executing", "s1", "EXECUTING"));
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        session.setLongRunningTaskId(taskId);
        session.setLongRunningTaskDirectory(tempDir
                .resolve(".mada/long-running").resolve(taskId).toString());
        return session;
    }

    @Test
    void executingTurnWithProgressDoesNotWriteWarning() throws Exception {
        ConversationSession session = executingSession("task-tracker-1");
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        // Simulate a successful tool execution (pending → commit)
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("action", "append_progress");
        tracker.onToolExecutionStarted("toolu_1", "longrun_task_update", input);
        tracker.onToolExecutionCompleted("toolu_1", true, 100);

        assertTrue(tracker.hasProgress());

        tracker.onTurnEnd();

        String progress = Files.readString(tempDir
                .resolve(".mada/long-running/task-tracker-1/progress.txt"));
        assertFalse(progress.contains("HARNESS WARNING"));
    }

    @Test
    void executingTurnWithoutProgressWritesWarning() throws Exception {
        ConversationSession session = executingSession("task-tracker-2");
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        assertFalse(tracker.hasProgress());

        tracker.onTurnEnd();

        String progress = Files.readString(tempDir
                .resolve(".mada/long-running/task-tracker-2/progress.txt"));
        assertTrue(progress.contains("HARNESS WARNING"));
    }

    @Test
    void completedTurnDoesNotTriggerWarning() throws Exception {
        ConversationSession session = executingSession("task-tracker-3");
        session.setLongRunningStage(LongRunningStage.COMPLETED);
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        tracker.onTurnEnd();

        String progress = Files.readString(tempDir
                .resolve(".mada/long-running/task-tracker-3/progress.txt"));
        assertFalse(progress.contains("HARNESS WARNING"));
    }

    @Test
    void cancelledTurnDoesNotTriggerWarning() throws Exception {
        ConversationSession session = executingSession("task-tracker-4");
        session.setLongRunningStage(LongRunningStage.CANCELLED);
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        tracker.onTurnEnd();

        String progress = Files.readString(tempDir
                .resolve(".mada/long-running/task-tracker-4/progress.txt"));
        assertFalse(progress.contains("HARNESS WARNING"));
    }

    @Test
    void nonLongRunningToolDoesNotCountAsProgress() throws Exception {
        ConversationSession session = executingSession("task-tracker-5");
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        ObjectNode input = new ObjectMapper().createObjectNode();
        tracker.onToolExecutionStarted("toolu_1", "file_read", input);

        assertFalse(tracker.hasProgress());

        tracker.onTurnEnd();

        String progress = Files.readString(tempDir
                .resolve(".mada/long-running/task-tracker-5/progress.txt"));
        assertTrue(progress.contains("HARNESS WARNING"));
    }
}