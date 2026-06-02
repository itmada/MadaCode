package madacode.cli.mode;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTurnAssignment;
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
import static org.junit.jupiter.api.Assertions.assertNull;

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
        AtomicReference<LongRunningTurnAssignment> seenAssignment = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            seenAssignment.set(session.longRunningTurnAssignment().orElse(null));
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
        assertNotNull(session.longRunningTaskId());
        assertNotNull(session.longRunningTaskDirectory());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());
        assertNotNull(seenAssignment.get());
        assertEquals(LongRunningTurnAssignment.Kind.SEED_FEATURE_LIST, seenAssignment.get().kind());
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
        AtomicReference<String> taskIdDuringTurn = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            stageDuringTurn.set(session.longRunningStage());
            taskIdDuringTurn.set(session.longRunningTaskId());
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
        assertNotNull(taskIdDuringTurn.get());
        assertEquals(taskIdDuringTurn.get(), session.longRunningTaskId());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
        LongRunningTaskStore store = new LongRunningTaskStore(session.workingDirectory());
        assertEquals("planning", store.loadTask(session.longRunningTaskId()).status());
        assertTrue(Files.isRegularFile(Path.of(session.longRunningTaskDirectory()).resolve("logs/events.jsonl")));
    }

    @Test
    void highConfidenceFinalizePlanMovesToWaitingForApprovalAfterTurn() {
        Path workingDirectory = tempDir.resolve("ws-plan");
        TurnExecutor executor = executor((turn, session, token) -> {
            session.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.PLANNING,
                    ConversationSession.LongRunningStageUpdateIntent.FINALIZE_PLAN,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "Plan discussion is complete.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_TASK);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("build app", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertEquals(LongRunningStage.WAITING_FOR_APPROVAL, session.longRunningStage());
        assertEquals("Plan discussion is complete.", session.longRunningPlanSummary());
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        assertEquals("planning", store.loadTask(session.longRunningTaskId()).status());
        assertEquals("WAITING_FOR_APPROVAL", store.loadTask(session.longRunningTaskId()).stage());
        assertTrue(store.readEvents(session.longRunningTaskId()).stream()
                .anyMatch(event -> "stage_transition".equals(event.type())
                        && "FINALIZE_PLAN".equals(event.action())
                        && "WAITING_FOR_APPROVAL".equals(event.stage())));
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
    void highConfidenceRevisePlanReturnsToPlanningAfterTurn() {
        Path workingDirectory = tempDir.resolve("ws-revise");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-planning", "Discuss plan", "planning", "session-revise", "WAITING_FOR_APPROVAL"));
        TurnExecutor executor = executor((turn, session, token) -> {
            session.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.WAITING_FOR_APPROVAL,
                    ConversationSession.LongRunningStageUpdateIntent.REVISE_PLAN,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "User wants to discuss details.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
        session.setLongRunningTaskId("task-planning");

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("还要商讨具体细节", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        assertEquals(LongRunningStage.PLANNING, session.longRunningStage());
        assertEquals("planning", store.loadTask("task-planning").status());
        assertEquals("PLANNING", store.loadTask("task-planning").stage());
        assertTrue(store.readEvents("task-planning").stream()
                .anyMatch(event -> "stage_transition".equals(event.type())
                        && "REVISE_PLAN".equals(event.action())
                        && "PLANNING".equals(event.stage())));
    }

    @Test
    void approvalPromotesExistingPlanningTask() throws Exception {
        Path workingDirectory = tempDir.resolve("ws-promote");
        TurnExecutor planningExecutor = executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_TASK);

        try {
            ModeExecution planning = new LongRunningModeHandler(planningExecutor).handle("build app", session);
            planning.handle().result().join();
            planning.afterTurn().run();
        } finally {
            planningExecutor.close();
        }

        String planningTaskId = session.longRunningTaskId();
        TurnExecutor approvalExecutor = executor((turn, s, token) -> {
            s.recordLongRunningStageUpdate(new ConversationSession.LongRunningStageUpdate(
                    LongRunningStage.WAITING_FOR_APPROVAL,
                    ConversationSession.LongRunningStageUpdateIntent.APPROVE_EXECUTION,
                    ConversationSession.LongRunningConfidence.HIGH,
                    "Approved.",
                    Instant.parse("2026-05-01T00:00:00Z")));
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        try {
            ModeExecution approval = new LongRunningModeHandler(approvalExecutor).handle("start", session);
            approval.handle().result().join();
            approval.afterTurn().run();
        } finally {
            approvalExecutor.close();
        }

        assertEquals(planningTaskId, session.longRunningTaskId());
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        assertEquals("executing", store.loadTask(planningTaskId).status());
        assertTrue(store.readEvents(planningTaskId).stream()
                .anyMatch(event -> "task_execution_started".equals(event.type())));
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

    @Test
    void executingStageWithoutTaskCreatesTaskBeforeTurn() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenInput.set(turn.userInput());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-create"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        // No taskId or taskDirectory set — handler must create them

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("new task", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("new task", seenInput.get());
        assertNotNull(session.longRunningTaskId());
        assertNotNull(session.longRunningTaskDirectory());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());
    }

    @Test
    void executingStageWithExistingTaskIdRepairsDirectoryBeforeTurn() throws Exception {
        Path workingDirectory = tempDir.resolve("ws-repair");
        // Create a real task store first
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-repair", "Repair test", "executing", "session-r", "EXECUTING"));
        Path realDir = store.taskDirectoryPath("task-repair");

        AtomicReference<String> seenDir = new AtomicReference<>();
        TurnExecutor executor = executor((turn, session, token) -> {
            seenDir.set(session.longRunningTaskDirectory());
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        });
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        session.setLongRunningTaskId("task-repair");
        // taskDirectory intentionally not set (simulates stale state)

        try {
            LongRunningModeHandler handler = new LongRunningModeHandler(executor);
            TurnHandle handle = handler.handle("repair", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals(realDir.toString(), seenDir.get());
        assertEquals(realDir.toString(), session.longRunningTaskDirectory());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
    }

    @Test
    void executingAfterTurnVerifiesAssignedTarget() {
        Path workingDirectory = tempDir.resolve("ws-verify");
        TurnExecutor executor = executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        try {
            ModeExecution execution = new LongRunningModeHandler(executor).handle("continue", session);
            execution.handle().result().join();
            execution.afterTurn().run();
        } finally {
            executor.close();
        }

        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        assertTrue(store.readEvents(session.longRunningTaskId()).stream()
                .anyMatch(event -> "assignment_verified".equals(event.type())
                        && Boolean.FALSE.equals(event.success())));
    }

    private TurnExecutor executor(TurnRunner runner) {
        return new TurnExecutor(runner, new TurnLog(tempDir.resolve("turns-" + System.nanoTime())));
    }

    private TurnExecutor executorForStageProbe() {
        return executor((turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1));
    }
}
