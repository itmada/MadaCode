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
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.LongRunningTaskStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void waitingForTaskMovesToPlanningBeforeTurn() {
        AtomicReference<LongRunningStage> stageDuringTurn = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            stageDuringTurn.set(session.longRunningStage());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-task"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_TASK);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("build the thing", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertEquals(LongRunningStage.PLANNING, stageDuringTurn.get());
        assertEquals(LongRunningStage.PLANNING, session.longRunningStage());
        assertEquals("build the thing", session.longRunningTaskTitle());
    }

    @Test
    void highConfidenceFinalizePlanMovesToWaitingForApprovalAfterTurn() {
        TurnExecutor executor = executor((turn, session, token) -> {
            session.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.PLANNING,
                    ConversationSession.LongRunningStageUpdateIntent.FINALIZE_PLAN,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "Plan discussion is complete.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-plan"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("looks good", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertEquals(LongRunningStage.WAITING_FOR_APPROVAL, session.longRunningStage());
        assertEquals("Plan discussion is complete.", session.longRunningPlanSummary());
    }

    @Test
    void highConfidenceApprovalCreatesTaskAndMovesToExecuting() throws Exception {
        Path workingDirectory = tempDir.resolve("ws-approval");
        TurnExecutor executor = executor((turn, session, token) -> {
            session.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.WAITING_FOR_APPROVAL,
                    ConversationSession.LongRunningStageUpdateIntent.APPROVE_EXECUTION,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "User explicitly approved execution.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("start", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());
        assertNotNull(session.longRunningTaskId());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
        assertEquals("start", session.inputHistory().getFirst());
        assertEquals("Long-running task",
                new LongRunningTaskStore(workingDirectory).loadTask(session.longRunningTaskId()).title());
        assertTrue(Files.isRegularFile(Path.of(session.longRunningTaskDirectory()).resolve("feature_list.json")));
        assertTrue(Files.isRegularFile(Path.of(session.longRunningTaskDirectory()).resolve("known-issues.json")));
        assertTrue(Files.readString(Path.of(session.longRunningTaskDirectory()).resolve("progress.txt"))
                .contains("INITIALIZING -> EXECUTING"));
    }

    @Test
    void approvalUsesOriginalTaskTitleInsteadOfApprovalUtterance() {
        Path workingDirectory = tempDir.resolve("ws-title");
        TurnExecutor executor = executor((turn, session, token) -> {
            session.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.WAITING_FOR_APPROVAL,
                    ConversationSession.LongRunningStageUpdateIntent.APPROVE_EXECUTION,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "Approved final plan.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
        session.setLongRunningTaskTitle("Implement durable long-running workflow");

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("start", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertEquals("Implement durable long-running workflow",
                new LongRunningTaskStore(workingDirectory).loadTask(session.longRunningTaskId()).title());
    }

    @Test
    void taskIdRetriesWhenTimestampDirectoryAlreadyExists() {
        Path workingDirectory = tempDir.resolve("ws-collision");
        LongRunningTaskStore preexisting = new LongRunningTaskStore(workingDirectory);
        String sameSecondId = "task-fixed";
        preexisting.createTask(new CreateTaskRequest(
                sameSecondId, "existing", "executing", "other-session", "EXECUTING"));

        TurnExecutor executor = executor((turn, session, token) -> {
            session.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.WAITING_FOR_APPROVAL,
                    ConversationSession.LongRunningStageUpdateIntent.APPROVE_EXECUTION,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "Approved final plan.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
        session.setLongRunningTaskTitle("New task");

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(
                    executor,
                    LongRunningTaskStore::new,
                    attempt -> attempt == 0 ? sameSecondId : sameSecondId + "-" + attempt);
            ModeExecution execution = handler.handle("start", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertTrue(session.longRunningTaskId().startsWith(sameSecondId));
        assertTrue(!session.longRunningTaskId().equals(sameSecondId));
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
    }

    private TurnExecutor executor(TurnRunner runner) {
        return new TurnExecutor(runner, new TurnLog(tempDir.resolve("turns-" + System.nanoTime())));
    }

    private TurnExecutor executorForStageProbe() {
        return executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
    }
}
