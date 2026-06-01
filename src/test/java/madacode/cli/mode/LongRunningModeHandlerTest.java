package madacode.cli.mode;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnHandle;
import madacode.core.turn.TurnLog;
import madacode.core.turn.TurnResult;
import madacode.core.turn.TurnRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningModeHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToPlanningStageWhenLegacyPlanModeIsEnabled() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPlanMode(true);

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("plan this", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("plan this", seenInput.get());
        assertEquals(LongRunningStage.PLANNING,
                new LongRunningModeHandler(executorForStageProbe()).stage(session));
    }

    @Test
    void acceptsExplicitExecutingStage() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-explicit"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("continue", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("continue", seenInput.get());
    }

    @Test
    void expandsAtFileMentionsAndRecordsInputBeforeSubmittingTurn() throws Exception {
        Path workingDirectory = tempDir.resolve("ws-files");
        Files.createDirectories(workingDirectory);
        Files.writeString(workingDirectory.resolve("note.txt"), "todo body");

        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("read @note.txt", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("read @note.txt", session.inputHistory().getFirst());
        assertTrue(seenInput.get().contains("<file path=\"note.txt\">"));
        assertTrue(seenInput.get().contains("todo body"));
    }

    private TurnExecutor executor(TurnRunner runner) {
        return new TurnExecutor(runner, new TurnLog(tempDir.resolve("turns-" + System.nanoTime())));
    }

    private TurnExecutor executorForStageProbe() {
        return executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
    }
}
