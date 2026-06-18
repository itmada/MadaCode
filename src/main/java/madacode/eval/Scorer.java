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

    Dimension dimension();

    /** Optional dimensions are omitted when their corresponding checks are absent. */
    boolean appliesTo(EvalCase evalCase);

    /** Whether a failed measurement blocks the attempt's final verdict. */
    boolean gating(EvalCase evalCase);

    DimensionScore score(EvalCase evalCase, ScoringContext context);

    /** Stable implementation/configuration identity recorded in the run manifest. */
    default String reproducibilityDescriptor() {
        return dimension() + "=" + getClass().getName();
    }

    default DimensionScore result(
            EvalCase evalCase,
            EvalResult.JudgeStatus status,
            String detail) {
        return new DimensionScore(dimension(), status, gating(evalCase), detail);
    }
}
