package madacode.eval;

/** Writes per-attempt debug artifacts without affecting attempt verdicts. */
public interface AttemptArtifactWriter {

    AttemptArtifactWriter NOOP = (evalCase, attemptNumber, evidence, result) -> AttemptArtifacts.NONE;

    AttemptArtifacts write(
            EvalCase evalCase,
            int attemptNumber,
            AttemptEvidence evidence,
            EvalResult result);
}
