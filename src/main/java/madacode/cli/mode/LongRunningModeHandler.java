package madacode.cli.mode;

import madacode.cli.AtFileCompleter;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.turn.TurnExecutor;

import java.util.Objects;

/**
 * Skeleton long-running workflow handler.
 *
 * <p>The current implementation still delegates every stage to the existing
 * query-engine turn pipeline, but it centralizes stage dispatch so later work
 * can attach task-store initialization, approval prompts, and execution hooks
 * without changing {@code Repl.handleLine}.
 */
public final class LongRunningModeHandler implements ModeHandler {

    private final TurnExecutor turnExecutor;

    public LongRunningModeHandler(TurnExecutor turnExecutor) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
    }

    @Override
    public ModeExecution handle(String line, ConversationSession session) {
        session.addInput(line);
        String expanded = AtFileCompleter.expandMentions(line, session);
        LongRunningStage stage = stage(session);
        return switch (stage) {
            case WAITING_FOR_TASK, PLANNING, WAITING_FOR_APPROVAL ->
                    runConversationalTurn(session, expanded, stage);
            case INITIALIZING, EXECUTING -> runExecutingTurn(session, expanded);
            case COMPLETED, CANCELLED -> runConversationalTurn(session, expanded, stage);
        };
    }

    private ModeExecution runConversationalTurn(
            ConversationSession session,
            String expanded,
            LongRunningStage stage) {
        beforeConversationStage(session, stage, expanded);
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    private ModeExecution runExecutingTurn(ConversationSession session, String expanded) {
        beforeExecutingStage(session, expanded);
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }

    /**
     * Hook for future long-running stage-specific prompt shaping / task setup.
     */
    protected void beforeConversationStage(
            ConversationSession session,
            LongRunningStage stage,
            String expandedInput) {
        // Integration point for long-running stage prompt shaping.
    }

    /**
     * Hook for future long-running execution bootstrapping.
     */
    protected void beforeExecutingStage(ConversationSession session, String expandedInput) {
        // Integration point for task execution initialization.
    }

    LongRunningStage stage(ConversationSession session) {
        LongRunningStage explicit = session.longRunningStage();
        if (explicit != null) return explicit;
        if (session.isPlanMode()) {
            return LongRunningStage.PLANNING;
        }
        return LongRunningStage.WAITING_FOR_TASK;
    }
}
