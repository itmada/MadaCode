package madacode.eval;

/**
 * Command-based scorer: runs the case's {@code verify.sh} in the sandbox and treats
 * exit code 0 as a pass. This is the most objective signal for a coding agent — it can
 * encode "tests pass", "code compiles", or "file contains expected snippet" — and mirrors
 * how SWE-bench-style harnesses judge real coding tasks.
 */
public final class VerifyScriptScorer implements Scorer {

    @Override
    public Dimension dimension() {
        return Dimension.VERIFY;
    }

    @Override
    public boolean appliesTo(EvalCase evalCase) {
        return true;
    }

    @Override
    public boolean gating(EvalCase evalCase) {
        return true;
    }

    @Override
    public DimensionScore score(EvalCase evalCase, ScoringContext context) {
        EvalExecutionEnvironment.VerifyOutcome outcome =
                context.environment().runVerify(context.budget());
        String detail = "exit=" + outcome.exitCode() + "\n" + outcome.output();
        EvalResult.JudgeStatus status = switch (outcome.status()) {
            case PASSED -> EvalResult.JudgeStatus.PASS;
            case FAILED -> EvalResult.JudgeStatus.FAIL;
            // A verify timeout usually means the candidate workspace made the objective
            // check hang (for example an infinite loop). Count that as a measured failure
            // instead of excluding it from the denominator as flaky infrastructure.
            case TIMED_OUT -> EvalResult.JudgeStatus.FAIL;
            case ERROR, INTERRUPTED -> EvalResult.JudgeStatus.ERROR;
        };
        return result(evalCase, status, detail);
    }
}
