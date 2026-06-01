package madacode.cli.mode;

import madacode.cli.AtFileCompleter;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnExecutor;
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.LongRunningTaskMetadata;
import madacode.longrunning.LongRunningTaskStore;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Stateful handler for long-running workflow turns.
 *
 * <p>Each user input still runs through the normal QueryEngine turn pipeline,
 * but this handler owns the workflow state transitions around that turn.
 */
public final class LongRunningModeHandler implements ModeHandler {

    private static final DateTimeFormatter TASK_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int MAX_TASK_ID_ATTEMPTS = 10;

    private final TurnExecutor turnExecutor;
    private final TaskStoreFactory taskStoreFactory;
    private final TaskIdGenerator taskIdGenerator;

    public LongRunningModeHandler(TurnExecutor turnExecutor) {
        this(turnExecutor, LongRunningTaskStore::new);
    }

    public LongRunningModeHandler(TurnExecutor turnExecutor, TaskStoreFactory taskStoreFactory) {
        this(turnExecutor, taskStoreFactory, LongRunningModeHandler::newTaskId);
    }

    LongRunningModeHandler(
            TurnExecutor turnExecutor,
            TaskStoreFactory taskStoreFactory,
            TaskIdGenerator taskIdGenerator) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator");
    }

    @Override
    public ModeExecution handle(String line, ConversationSession session) {
        ensureLongRunningSession(session);
        session.addInput(line);
        String expanded = AtFileCompleter.expandMentions(line, session);
        LongRunningStage stage = stage(session);
        if (stage == LongRunningStage.WAITING_FOR_TASK) {
            session.setLongRunningStage(LongRunningStage.PLANNING);
            if (session.longRunningTaskTitle() == null) {
                session.setLongRunningTaskTitle(taskTitle(expanded));
            }
            session.clearLongRunningStageUpdate();
            stage = LongRunningStage.PLANNING;
        }
        if (stage == LongRunningStage.INITIALIZING) {
            initializeTask(session, expanded);
            stage = LongRunningStage.EXECUTING;
        }

        ModeExecution execution = switch (stage) {
            case PLANNING, WAITING_FOR_APPROVAL ->
                    runConversationalTurn(session, expanded);
            case EXECUTING ->
                    runExecutingTurn(session, expanded);
            case COMPLETED, CANCELLED ->
                    runConversationalTurn(session, expanded);
            case WAITING_FOR_TASK, INITIALIZING ->
                    throw new IllegalStateException("Unexpected long-running stage after preflight: " + stage);
        };
        return new ModeExecution(execution.handle(), () -> applyStageUpdate(session, expanded));
    }

    private ModeExecution runConversationalTurn(ConversationSession session, String expanded) {
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    private ModeExecution runExecutingTurn(ConversationSession session, String expanded) {
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    LongRunningStage stage(ConversationSession session) {
        LongRunningStage explicit = session.longRunningStage();
        if (explicit != null) return explicit;
        if (session.isPlanMode()) {
            return LongRunningStage.PLANNING;
        }
        return LongRunningStage.WAITING_FOR_TASK;
    }

    private void applyStageUpdate(ConversationSession session, String expandedInput) {
        var update = session.lastLongRunningStageUpdate().orElse(null);
        if (update == null || update.confidence() != ConversationSession.LongRunningConfidence.HIGH) {
            return;
        }
        if (session.longRunningStage() != update.stage()) {
            session.clearLongRunningStageUpdate();
            return;
        }
        switch (update.intent()) {
            case FINALIZE_PLAN -> {
                if (session.longRunningStage() == LongRunningStage.PLANNING) {
                    session.setLongRunningPlanSummary(update.summary());
                    session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
                }
            }
            case APPROVE_EXECUTION -> {
                if (session.longRunningStage() == LongRunningStage.WAITING_FOR_APPROVAL) {
                    session.setLongRunningStage(LongRunningStage.INITIALIZING);
                    initializeTask(session, expandedInput);
                }
            }
            case CANCEL -> session.setLongRunningStage(LongRunningStage.CANCELLED);
            case COMPLETE -> session.setLongRunningStage(LongRunningStage.COMPLETED);
        }
    }

    private void initializeTask(ConversationSession session, String expandedInput) {
        if (session.longRunningTaskId() != null) {
            taskStoreFactory.create(session.workingDirectory())
                    .validateTaskDirectory(session.longRunningTaskId());
            session.setLongRunningStage(LongRunningStage.EXECUTING);
            return;
        }
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        LongRunningTaskMetadata metadata = createTaskWithFreshId(store, session);
        Path taskDirectory = session.workingDirectory()
                .resolve(".mada/long-running")
                .resolve(metadata.id())
                .normalize();
        session.setLongRunningTaskId(metadata.id());
        session.setLongRunningTaskDirectory(taskDirectory.toString());
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        store.appendProgress(metadata.id(), initialProgressEntry(session, expandedInput));
    }

    private LongRunningTaskMetadata createTaskWithFreshId(
            LongRunningTaskStore store,
            ConversationSession session) {
        for (int attempt = 0; attempt < MAX_TASK_ID_ATTEMPTS; attempt++) {
            String taskId = taskIdGenerator.newTaskId(attempt);
            try {
                return store.createTask(new CreateTaskRequest(
                        taskId,
                        durableTaskTitle(session),
                        "executing",
                        session.sessionId(),
                        LongRunningStage.EXECUTING.name()));
            } catch (madacode.longrunning.LongRunningTaskStoreException exception) {
                if (!exception.getMessage().contains("already exists")) {
                    throw exception;
                }
            }
        }
        String fallbackId = "task-" + TASK_ID_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        return store.createTask(new CreateTaskRequest(
                fallbackId,
                durableTaskTitle(session),
                "executing",
                session.sessionId(),
                LongRunningStage.EXECUTING.name()));
    }

    private static String newTaskId(int attempt) {
        String base = "task-" + TASK_ID_TIME.format(Instant.now());
        return attempt == 0 ? base : base + "-" + attempt;
    }

    private static String durableTaskTitle(ConversationSession session) {
        if (session.longRunningTaskTitle() != null) {
            return session.longRunningTaskTitle();
        }
        if (session.longRunningPlanSummary() != null) {
            return taskTitle(session.longRunningPlanSummary());
        }
        return "Long-running task";
    }

    private static String taskTitle(String input) {
        String normalized = input == null ? "" : input.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "Long-running task";
        }
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    private static String initialProgressEntry(ConversationSession session, String input) {
        return """
                ## %s
                stage: INITIALIZING -> EXECUTING
                session: %s
                task: %s
                initial input: %s

                """.formatted(
                Instant.now(),
                session.sessionId(),
                session.longRunningTaskId(),
                input == null ? "" : input.strip());
    }

    private static void ensureLongRunningSession(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            session.setWorkflowMode(SessionMode.LONG_RUNNING);
        }
        if (session.longRunningStage() == null) {
            session.setLongRunningStage(LongRunningStage.WAITING_FOR_TASK);
        }
    }

    @FunctionalInterface
    public interface TaskStoreFactory {
        LongRunningTaskStore create(Path projectDirectory);
    }

    @FunctionalInterface
    interface TaskIdGenerator {
        String newTaskId(int attempt);
    }
}
