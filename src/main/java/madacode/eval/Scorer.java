package madacode.eval;

/**
 * Maps a finished run (the agent's effect on the sandbox) to a pass/fail score.
 *
 * <p>An interface, not a concrete class, so different judging strategies can plug in
 * without touching {@link EvalRunner}: the default {@link VerifyScriptScorer} is
 * command-based (objective, SWE-bench style), and a future LLM-as-a-judge scorer for
 * fuzzy qualities (e.g. plan quality) can be added behind the same seam.
 */
public interface Scorer {

    Score score(EvalCase evalCase, EvalExecutionEnvironment environment, RunBudget budget);

    /** A single case's judged result. */
    record Score(EvalResult.JudgeStatus status, int exitCode, String detail) {
        public boolean passed() {
            return status == EvalResult.JudgeStatus.PASS;
        }
    }
}
