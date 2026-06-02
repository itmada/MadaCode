package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification test: failed tool calls should NOT count as progress.
 *
 * <p>The tracker uses a pending→commit pattern:
 * {@code onToolExecutionStarted} records to a pending map, and only
 * {@code onToolExecutionCompleted(success=true)} promotes to recorded.
 * A failed tool call correctly does NOT count as progress.
 */
class LongRunningTurnTrackerFailedToolTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;
    private ConversationSession session;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-fail", "Test", "executing", "s1", "EXECUTING"));

        session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        session.setLongRunningTaskId("task-fail");
        session.setLongRunningTaskDirectory(
                tempDir.resolve(".mada/long-running/task-fail").toString());
    }

    @Test
    void failedToolCallShouldNotCountAsProgress() {
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        // Simulate the ToolExecutor pending→commit flow:
        // 1. onToolExecutionStarted records to pending map
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("action", "append_progress");
        input.put("text", "will fail");
        tracker.onToolExecutionStarted("toolu_1", "longrun_task_update", input);

        // Step 1: after start only, hasProgress is false (nothing committed yet)
        assertFalse(tracker.hasProgress(),
                "After onToolExecutionStarted, progress should not be committed yet");

        // 2. Tool fails — onToolExecutionCompleted(success=false) removes from pending, does NOT commit
        tracker.onToolExecutionCompleted("toolu_1", false, 100);

        assertFalse(tracker.hasProgress(),
                "Failed tool call should not count as progress");
    }

    @Test
    void successfulToolCallCountsAsProgress() {
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("action", "append_progress");
        input.put("text", "real progress");
        tracker.onToolExecutionStarted("toolu_1", "longrun_task_update", input);

        // Tool succeeds
        tracker.onToolExecutionCompleted("toolu_1", true, 100);

        // Now hasProgress should be true
        assertTrue(tracker.hasProgress(),
                "Successful tool call should count as progress");
    }

    @Test
    void nonLongRunningToolShouldNotCountAsProgress() {
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);

        ObjectNode input = new ObjectMapper().createObjectNode();
        tracker.onToolExecutionStarted("toolu_1", "file_read", input);

        // file_read is not a long-running tool → should NOT count as progress
        assertFalse(tracker.hasProgress(),
                "Non-longrunning tools should not count as progress");
    }
}