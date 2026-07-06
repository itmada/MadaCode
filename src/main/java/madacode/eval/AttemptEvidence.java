package madacode.eval;

/** Attempt-scoped evidence that can be written after scoring completes. */
public record AttemptEvidence(
        ExecutionTrace trace,
        EvalExecutionEnvironment.VerifyOutcome verifyOutcome) {
}
