package madacode.eval;

import madacode.core.engine.QueryEngine;
import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnResult;

import java.util.List;

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
        List<ConversationTurn> conversation = evalCase.effectiveConversation();
        int totalIterations = 0;
        String finalText = "";
        try {
            TurnResult turn = null;
            for (ConversationTurn scriptedTurn : conversation) {
                if (scriptedTurn.trigger() == ConversationTurn.Trigger.WHEN_AGENT_ASKS
                        && !looksLikeQuestion(finalText)) {
                    break;
                }
                turn = context.runtime().runTurn(
                        engine, session, scriptedTurn.text(), context.remainingTime());
                totalIterations += turn.iterations();
                finalText = turn.finalText();
                if (turn.finishReason() != FinishReason.COMPLETED) {
                    break;
                }
            }
            if (turn == null) {
                throw new IllegalStateException("conversation did not execute a user turn");
            }
            return new LaunchOutcome(
                    executionStatus(turn.finishReason()),
                    RunMetrics.fromSession(session, totalIterations),
                    terminalSummary(turn),
                    detail(turn),
                    finalText,
                    true,
                    turn.apiFailure());
        } catch (madacode.bootstrap.HeadlessAgentRuntime.HeadlessTurnTimeoutException e) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.TIMED_OUT,
                    RunMetrics.fromSession(session, totalIterations),
                    "TIMED_OUT",
                    e.getMessage(),
                    finalText,
                    e.quiescent());
        }
    }

    private static boolean looksLikeQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("?")
                || normalized.contains("？")
                || normalized.matches("(?s).*(请问|能否|可以提供|需要.*吗|which|what|could you|can you).*");
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

    private static String terminalSummary(TurnResult turn) {
        return turn.apiFailure() == null
                ? turn.finishReason().name()
                : turn.finishReason().name() + " " + turn.apiFailure().detail();
    }

    private static String detail(TurnResult turn) {
        return turn.apiFailure() == null
                ? turn.finalText()
                : turn.finalText() + "\n" + turn.apiFailure().detail();
    }
}
