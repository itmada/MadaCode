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

/**
 * Stateful handler for long-running workflow turns.
 *
 * <p>Each user input still runs through the normal QueryEngine turn pipeline,
 * but this handler owns the workflow state transitions around that turn.
 */
public final class LongRunningModeHandler implements ModeHandler {

    private static final DateTimeFormatter TASK_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final TurnExecutor turnExecutor;
    private final TaskStoreFactory taskStoreFactory;

    public LongRunningModeHandler(TurnExecutor turnExecutor) {
        this(turnExecutor, LongRunningTaskStore::new);
    }

    public LongRunningModeHandler(TurnExecutor turnExecutor, TaskStoreFactory taskStoreFactory) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
    }

    @Override
    public ModeExecution handle(String line, ConversationSession session) {
        ensureLongRunningSession(session);
        session.addInput(line);
        String expanded = AtFileCompleter.expandMentions(line, session);
        LongRunningStage stage = stage(session);
        if (stage == LongRunningStage.WAITING_FOR_TASK) {
            session.setLongRunningStage(LongRunningStage.PLANNING);
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
        String taskId = newTaskId();
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        LongRunningTaskMetadata metadata = store.createTask(new CreateTaskRequest(
                taskId,
                taskTitle(expandedInput),
                "executing",
                session.sessionId(),
                LongRunningStage.EXECUTING.name()));
        Path taskDirectory = session.workingDirectory()
                .resolve(".mada/long-running")
                .resolve(metadata.id())
                .normalize();
        session.setLongRunningTaskId(metadata.id());
        session.setLongRunningTaskDirectory(taskDirectory.toString());
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        store.appendProgress(metadata.id(), initialProgressEntry(session, expandedInput));
    }

    private static String newTaskId() {
        return "task-" + TASK_ID_TIME.format(Instant.now());
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
}
