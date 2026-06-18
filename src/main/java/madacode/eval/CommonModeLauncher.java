package madacode.eval;

import madacode.core.engine.QueryEngine;
import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnResult;

/**
 * Common-mode launcher: the everyday interactive workflow. Hands the instruction to the
 * shared {@link QueryEngine#runTurn} core — the exact loop the REPL drives — and lets it
 * iterate tool calls until done.
 *
 * <p>Plan mode is exercised here as the transitional {@code planMode} flag on a common
 * case. When plan is promoted to a top-level {@code plan-and-execute} workflow, that moves
 * to its own launcher (see {@link ModeLauncherRegistry}); this launcher stays common-only.
 */
public final class CommonModeLauncher implements ModeLauncher {

    @Override
    public String modeId() {
        return "common";
    }

    @Override
    public LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context) {
        session.setWorkflowMode(SessionMode.COMMON);
        if (evalCase.planMode()) {
            session.setPlanMode(true);
        }
        QueryEngine engine = context.runtime().newEngine(context.budget().maxIterations());
        try {
            TurnResult turn = context.runtime().runTurn(
                    engine, session, evalCase.instruction(), context.remainingTime());
            return new LaunchOutcome(
                    executionStatus(turn.finishReason()),
                    RunMetrics.fromSession(session, turn.iterations()),
                    turn.finishReason().name(),
                    turn.finalText());
        } catch (madacode.bootstrap.HeadlessAgentRuntime.HeadlessTurnTimeoutException e) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.TIMED_OUT,
                    RunMetrics.fromSession(session, 0),
                    "TIMED_OUT",
                    e.getMessage(),
                    e.quiescent());
        }
    }

    private static EvalResult.ExecutionStatus executionStatus(FinishReason reason) {
        return switch (reason) {
            case COMPLETED -> EvalResult.ExecutionStatus.COMPLETED;
            case MAX_ITERATIONS -> EvalResult.ExecutionStatus.MAX_ITERATIONS;
            case API_ERROR -> EvalResult.ExecutionStatus.API_ERROR;
            case MODEL_TRUNCATED -> EvalResult.ExecutionStatus.MODEL_TRUNCATED;
            case PERMISSION_CANCELLED -> EvalResult.ExecutionStatus.PERMISSION_DENIED;
            case CANCELLED -> EvalResult.ExecutionStatus.CANCELLED;
        };
    }
}
