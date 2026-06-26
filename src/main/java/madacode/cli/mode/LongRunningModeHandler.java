package madacode.cli.mode;

import madacode.cli.AtFileCompleter;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnExecutor;
import java.util.Objects;

/**
 * Stateful handler for long-running workflow turns.
 *
 * <p>Each user input still runs through the normal QueryEngine turn pipeline,
 * but this handler owns the workflow state transitions around that turn.
 *
 * <p>State transition logic is delegated to {@link LongRunningController}.
 */
public final class LongRunningModeHandler implements ModeHandler {

    private final TurnExecutor turnExecutor;

    public LongRunningModeHandler(TurnExecutor turnExecutor) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
    }

    public LongRunningModeHandler(
            TurnExecutor turnExecutor,
            madacode.longrunning.LongRunningController.TaskStoreFactory taskStoreFactory) {
        this(turnExecutor);
    }

    LongRunningModeHandler(
            TurnExecutor turnExecutor,
            madacode.longrunning.LongRunningController.TaskStoreFactory taskStoreFactory,
            madacode.longrunning.LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator) {
        this(turnExecutor);
    }

    @Override
    public ModeExecution handle(String line, ConversationSession session) {
        ensureLongRunningSession(session);
        LongRunningStage stage = stage(session);
        if (stage == LongRunningStage.RUNNING) {
            return ModeExecution.managedTurn(turnExecutor.submitLocal(session,
                    "long-running monitor active",
                    (s, token) -> new madacode.core.turn.TurnResult(
                            "Long-running workers are running in the monitor. Press ESC in the monitor to interrupt before sending controller instructions.",
                            madacode.core.model.FinishReason.COMPLETED,
                            0)));
        }

        session.addInput(line);
        String expanded = AtFileCompleter.expandMentions(line, session);
        return runConversationalTurn(session, expanded);
    }

    private ModeExecution runConversationalTurn(ConversationSession session, String expanded) {
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    LongRunningStage stage(ConversationSession session) {
        LongRunningStage explicit = session.longRunningStage();
        if (explicit != null) return explicit;
        return LongRunningStage.DRAFT;
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
