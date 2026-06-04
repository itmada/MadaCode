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
import madacode.longrunning.LongRunningTaskStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class LongRunningModeHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToDraftStageWhenLegacyPlanModeIsEnabled() {
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
        assertEquals(LongRunningStage.DRAFT,
                new LongRunningModeHandler(executorForStageProbe()).stage(session));
    }

    @Test
    void acceptsExplicitRunningStage() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-explicit"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("continue", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("continue", seenInput.get());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertNull(session.longRunningTurnAssignment().orElse(null));
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

    @Test
    void draftStageKeepsDraftAndInitializesTaskShellBeforeTurn() {
        AtomicReference<LongRunningStage> stageDuringTurn = new AtomicReference<>();
        AtomicReference<String> taskIdDuringTurn = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            stageDuringTurn.set(session.longRunningStage());
            taskIdDuringTurn.set(session.longRunningTaskId());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-task"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("build the thing", session);
            execution.handle().result().join();
            assertTrue(execution.afterTurn().run().isEmpty(), "simplified handler should not compose afterTurn");
        } finally {
            executor.close();
        }

        assertEquals(LongRunningStage.DRAFT, stageDuringTurn.get());
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertEquals("build the thing", session.longRunningTaskTitle());
        assertNotNull(taskIdDuringTurn.get());
        assertEquals(taskIdDuringTurn.get(), session.longRunningTaskId());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
        LongRunningTaskStore store = new LongRunningTaskStore(session.workingDirectory());
        assertEquals("draft", store.loadTask(session.longRunningTaskId()).status());
        assertTrue(Files.isRegularFile(Path.of(session.longRunningTaskDirectory()).resolve("logs/events.jsonl")));
    }

    @Test
    void runningStageRunsConversationalTurnWithoutCreatingTask() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-create"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("status update", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("status update", seenInput.get());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertNull(session.longRunningTaskId());
    }

    @Test
    void runningStageRunsConversationalTurnEvenWithExistingTaskId() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-repair"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-repair");

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("check status", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("check status", seenInput.get());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("task-repair", session.longRunningTaskId());
    }

    @Test
    void runningControlSessionDoesNotVerifyAssignment() {
        TurnExecutor executor = executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-verify"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("continue", session);
            execution.handle().result().join();
            assertTrue(execution.afterTurn().run().isEmpty(), "simplified handler should not compose afterTurn");
        } finally {
            executor.close();
        }

        assertNull(session.longRunningTaskId());
    }

    private TurnExecutor executor(TurnRunner runner) {
        return new TurnExecutor(runner, new TurnLog(tempDir.resolve("turns-" + System.nanoTime())));
    }

    private TurnExecutor executorForStageProbe() {
        return executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
    }
}
