package madacode.eval;

import madacode.core.session.ConversationSession;
import madacode.services.api.ApiFailureClassification;

/**
 * Launches one eval case through a specific workflow mode's <em>real</em> entry point,
 * feeding the task in and letting the production agent pipeline run to completion.
 *
 * <p>This is the "executor" seam: a thin per-mode adapter that knows how to drive that
 * mode headlessly (common → {@code runTurn}; long-running → the worker launch loop). The
 * agent's intelligence is reused unchanged — a launcher only injects the task and waits.
 *
 * <p>★ Extensibility: modes are registered by {@link #modeId()} in {@link ModeLauncherRegistry}.
 * Adding a future mode (e.g. {@code plan-and-execute}) means writing one new launcher and
 * registering it — {@link EvalRunner}, {@link Scorer}, the report, and all case data are
 * untouched.
 */
public interface ModeLauncher {

    /** The {@code mode} value in {@code case.json} this launcher handles (e.g. "common"). */
    String modeId();

    /**
     * Runs the case. The runner supplies an environment-rooted {@code session} (with the case's
     * permission mode already applied); the launcher drives the real pipeline and returns
     * typed outcome and complete metrics. The agent's effect is judged afterwards by a
     * {@link Scorer}.
     */
    LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context);

    /** Typed execution outcome plus metrics aggregated by the workflow driver. */
    record LaunchOutcome(
            EvalResult.ExecutionStatus status,
            RunMetrics metrics,
            String terminalSummary,
            String detail,
            String finalText,
            boolean quiescent,
            ApiFailureClassification apiFailure) {

        public LaunchOutcome(
                EvalResult.ExecutionStatus status,
                RunMetrics metrics,
                String terminalSummary,
                String detail) {
            this(status, metrics, terminalSummary, detail, detail, true, null);
        }

        public LaunchOutcome(
                EvalResult.ExecutionStatus status,
                RunMetrics metrics,
                String terminalSummary,
                String detail,
                boolean quiescent) {
            this(status, metrics, terminalSummary, detail, detail, quiescent, null);
        }

        public LaunchOutcome(
                EvalResult.ExecutionStatus status,
                RunMetrics metrics,
                String terminalSummary,
                String detail,
                String finalText,
                boolean quiescent) {
            this(status, metrics, terminalSummary, detail, finalText, quiescent, null);
        }

        public boolean transientProviderFailure() {
            return apiFailure != null && apiFailure.transientProviderFailure();
        }
    }
}
