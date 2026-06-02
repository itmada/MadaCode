package madacode.cli.mode;

import madacode.cli.AtFileCompleter;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTurnAssignment;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnHandle;
import madacode.longrunning.LongRunningTaskEvent;
import madacode.longrunning.LongRunningTaskInitializer;
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.LongRunningTargetPlanner;
import madacode.longrunning.LongRunningPostTurnVerifier;
import madacode.longrunning.LongRunningTurnTracker;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Stateful handler for long-running workflow turns.
 *
 * <p>Each user input still runs through the normal QueryEngine turn pipeline,
 * but this handler owns the workflow state transitions around that turn.
 */
public final class LongRunningModeHandler implements ModeHandler {

    private final TurnExecutor turnExecutor;
    private final TaskStoreFactory taskStoreFactory;
    private final LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator;

    public LongRunningModeHandler(TurnExecutor turnExecutor) {
        this(turnExecutor, LongRunningTaskStore::new);
    }

    public LongRunningModeHandler(TurnExecutor turnExecutor, TaskStoreFactory taskStoreFactory) {
        this(turnExecutor, taskStoreFactory, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    LongRunningModeHandler(
            TurnExecutor turnExecutor,
            TaskStoreFactory taskStoreFactory,
            LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator) {
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
            initializePlanningTask(session, expanded);
            session.clearLongRunningStageUpdate();
            stage = LongRunningStage.PLANNING;
        }
        if (stage == LongRunningStage.INITIALIZING || stage == LongRunningStage.EXECUTING) {
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
        // Compose the tracker cleanup (if any) with the standard stage-update callback.
        Runnable existingAfterTurn = execution.afterTurn();
        return new ModeExecution(execution.handle(), () -> {
            existingAfterTurn.run();
            applyStageUpdate(session, expanded);
        });
    }

    private ModeExecution runConversationalTurn(ConversationSession session, String expanded) {
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    private ModeExecution runExecutingTurn(ConversationSession session, String expanded) {
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        LongRunningTurnAssignment assignment = new LongRunningTargetPlanner(store)
                .assign(session.longRunningTaskId());
        session.setLongRunningTurnAssignment(assignment);
        appendAssignmentEvent(session, store, assignment);
        LongRunningTurnTracker tracker = new LongRunningTurnTracker(session, store);
        session.addListener(tracker);
        TurnHandle handle = turnExecutor.submit(session, expanded);
        return ModeExecution.managedTurn(handle, () -> {
            session.removeListener(tracker);
            // The tracker's onTurnEnd() fires during the turn, but if it
            // hasn't been called yet (e.g. exception path), audit now.
            if (!tracker.hasProgress() && session.longRunningStage() == LongRunningStage.EXECUTING) {
                tracker.onTurnEnd();
            }
            if (session.longRunningTaskId() != null
                    && session.longRunningStage() != LongRunningStage.CANCELLED) {
                new LongRunningPostTurnVerifier(store)
                        .verify(session.longRunningTaskId(), session.sessionId(), assignment);
            }
        });
    }

    private void appendAssignmentEvent(
            ConversationSession session,
            LongRunningTaskStore store,
            LongRunningTurnAssignment assignment) {
        try {
            store.appendEvent(session.longRunningTaskId(), LongRunningTaskEvent.of(
                    "target_assigned",
                    session.longRunningTaskId(),
                    session.sessionId(),
                    session.longRunningStage() == null ? null : session.longRunningStage().name(),
                    assignment.kind().name(),
                    true,
                    assignment.description(),
                    Map.of(
                            "targetId", assignment.id() == null ? "" : assignment.id(),
                            "reason", assignment.reason() == null ? "" : assignment.reason())));
        } catch (RuntimeException ignored) {
            // Assignment is already in session; event logging is diagnostic.
        }
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
        LongRunningStage fromStage = session.longRunningStage();
        if (fromStage != update.stage()) {
            session.clearLongRunningStageUpdate();
            return;
        }
        switch (update.intent()) {
            case FINALIZE_PLAN -> {
                if (fromStage == LongRunningStage.PLANNING) {
                    markPlanAwaitingApproval(session);
                    session.setLongRunningPlanSummary(update.summary());
                    session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
                    appendStageTransitionEvent(session, update, fromStage, LongRunningStage.WAITING_FOR_APPROVAL);
                }
            }
            case APPROVE_EXECUTION -> {
                if (fromStage == LongRunningStage.WAITING_FOR_APPROVAL) {
                    session.setLongRunningStage(LongRunningStage.INITIALIZING);
                    initializeTask(session, expandedInput);
                    appendStageTransitionEvent(session, update, fromStage, LongRunningStage.EXECUTING);
                }
            }
            case REVISE_PLAN -> {
                if (fromStage == LongRunningStage.WAITING_FOR_APPROVAL) {
                    markPlanRevision(session);
                    session.setLongRunningStage(LongRunningStage.PLANNING);
                    appendStageTransitionEvent(session, update, fromStage, LongRunningStage.PLANNING);
                }
            }
            case CANCEL -> {
                session.setLongRunningStage(LongRunningStage.CANCELLED);
                appendStageTransitionEvent(session, update, fromStage, LongRunningStage.CANCELLED);
            }
        }
    }

    private void markPlanAwaitingApproval(ConversationSession session) {
        if (session.longRunningTaskId() == null) {
            return;
        }
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        store.markPlanAwaitingApproval(session.longRunningTaskId());
    }

    private void markPlanRevision(ConversationSession session) {
        if (session.longRunningTaskId() == null) {
            return;
        }
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        store.markPlanRevision(session.longRunningTaskId());
    }

    private void appendStageTransitionEvent(
            ConversationSession session,
            ConversationSession.LongRunningStageUpdate update,
            LongRunningStage fromStage,
            LongRunningStage toStage) {
        if (session.longRunningTaskId() == null) {
            return;
        }
        try {
            LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
            store.appendEvent(session.longRunningTaskId(), LongRunningTaskEvent.of(
                    "stage_transition",
                    session.longRunningTaskId(),
                    session.sessionId(),
                    toStage.name(),
                    update.intent().name(),
                    true,
                    update.summary(),
                    Map.of(
                            "fromStage", fromStage.name(),
                            "toStage", toStage.name(),
                            "confidence", update.confidence().wireValue())));
        } catch (RuntimeException ignored) {
            // Stage has already changed in session; event logging is diagnostic.
        }
    }

    private void initializeTask(ConversationSession session, String expandedInput) {
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        LongRunningTaskInitializer initializer =
                new LongRunningTaskInitializer(store, taskIdGenerator);
        initializer.ensureExecutionTask(session, expandedInput);
    }

    private void initializePlanningTask(ConversationSession session, String expandedInput) {
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        LongRunningTaskInitializer initializer =
                new LongRunningTaskInitializer(store, taskIdGenerator);
        initializer.ensurePlanningTask(session, expandedInput);
    }

    private static String taskTitle(String input) {
        return LongRunningTaskInitializer.taskTitle(input);
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
