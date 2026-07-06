package madacode.eval;

import java.util.Objects;

/** Shared evidence and execution boundary supplied to every dimensional scorer. */
public record ScoringContext(
        EvalExecutionEnvironment environment,
        ExecutionTrace trace,
        RunBudget budget,
        AttemptEvidenceRecorder evidenceRecorder) {

    public ScoringContext(
            EvalExecutionEnvironment environment,
            ExecutionTrace trace,
            RunBudget budget) {
        this(environment, trace, budget, new AttemptEvidenceRecorder());
    }

    public ScoringContext {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(budget, "budget");
        evidenceRecorder = evidenceRecorder == null ? new AttemptEvidenceRecorder() : evidenceRecorder;
    }
}
