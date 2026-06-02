package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification test for Bug #2: duplicate HARNESS WARNING.
 *
 * <p>The Repl calls {@code session.fireTurnEnd()} first, which triggers
 * {@code tracker.onTurnEnd()}, then later calls the afterTurn callback,
 * which may call {@code tracker.onTurnEnd()} again for the same turn.
 * This test simulates that exact sequence to confirm the duplication.
 */
class LongRunningTurnTrackerDuplicateWarningTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;
    private ConversationSession session;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-dup", "Test", "executing", "s1", "EXECUTING"));

        session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        session.setLongRunningTaskId("task-dup");
        session.setLongRunningTaskDirectory(
                tempDir.resolve(".mada/long-running/task-dup").toString());
    }

    @Test
    void duplicateWarningWhenAfterTurnCallsOnTurnEndAgain() throws Exception {
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);
        session.addListener(tracker);

        // Step 1: simulate Repl's fireTurnEnd() — this is called after the turn completes
        session.fireTurnEnd();
        // tracker.onTurnEnd() is invoked → writes warning #1

        // Step 2: simulate Repl's afterTurn callback
        // This is what LongRunningModeHandler composes:
        session.removeListener(tracker);
        if (!tracker.hasProgress() && session.longRunningStage() == LongRunningStage.EXECUTING) {
            tracker.onTurnEnd();
            // → writes warning #2 ← BUG: duplicate!
        }

        // Read progress.txt and count HARNESS WARNING occurrences
        String progress = Files.readString(
                tempDir.resolve(".mada/long-running/task-dup/progress.txt"));
        long count = progress.lines()
                .filter(line -> line.contains("HARNESS WARNING"))
                .count();
        assertTrue(count <= 1,
                "Expected at most 1 HARNESS WARNING but found " + count
                + ". The afterTurn callback is duplicating the warning written by fireTurnEnd.");
    }
}