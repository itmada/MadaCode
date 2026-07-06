package madacode.eval;

/** Mutable attempt-scoped recorder shared by scorers through {@link ScoringContext}. */
public final class AttemptEvidenceRecorder {

    private EvalExecutionEnvironment.VerifyOutcome verifyOutcome;

    public void recordVerifyOutcome(EvalExecutionEnvironment.VerifyOutcome outcome) {
        this.verifyOutcome = outcome;
    }

    public AttemptEvidence evidence(ExecutionTrace trace) {
        return new AttemptEvidence(trace, verifyOutcome);
    }
}
