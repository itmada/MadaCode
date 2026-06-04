package madacode.cli.mode;

import madacode.cli.AtFileCompleter;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnExecutor;
import madacode.longrunning.LongRunningController;
import madacode.longrunning.LongRunningTaskInitializer;
import madacode.longrunning.LongRunningTaskStore;

import java.util.Objects;

/**
 * Stateful handler for long-running workflow turns.
 *
 * <p>Each user input still runs through the normal QueryEngine turn pipeline.
 * This handler only keeps the control session in a valid DRAFT/RUNNING/DONE shape.
 */
public final class LongRunningModeHandler implements ModeHandler {

    private final TurnExecutor turnExecutor;
    private final LongRunningController.TaskStoreFactory taskStoreFactory;
    private final LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator;

    public LongRunningModeHandler(TurnExecutor turnExecutor) {
        this(turnExecutor, LongRunningTaskStore::new);
    }

    public LongRunningModeHandler(TurnExecutor turnExecutor, LongRunningController.TaskStoreFactory taskStoreFactory) {
        this(turnExecutor, taskStoreFactory, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    LongRunningModeHandler(
            TurnExecutor turnExecutor,
            LongRunningController.TaskStoreFactory taskStoreFactory,
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
        if (stage(session) == LongRunningStage.DRAFT) {
            initializeDraftTask(session, expanded);
        }
        return runConversationalTurn(session, expanded);
    }

    private ModeExecution runConversationalTurn(ConversationSession session, String expanded) {
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    LongRunningStage stage(ConversationSession session) {
        LongRunningStage explicit = session.longRunningStage();
        if (explicit != null) {
            return explicit;
        }
        return LongRunningStage.DRAFT;
    }

    private void initializeDraftTask(ConversationSession session, String expandedInput) {
        LongRunningTaskStore store = taskStoreFactory.create(session.workingDirectory());
        LongRunningTaskInitializer initializer =
                new LongRunningTaskInitializer(store, taskIdGenerator);
        initializer.ensurePlanningTask(session, expandedInput);
    }

    private static void ensureLongRunningSession(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            session.setWorkflowMode(SessionMode.LONG_RUNNING);
        }
        if (session.longRunningStage() == null) {
            session.setLongRunningStage(LongRunningStage.DRAFT);
        }
    }
}
