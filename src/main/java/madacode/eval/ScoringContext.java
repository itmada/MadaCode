package madacode.eval;

import java.util.Objects;

/** Shared evidence and execution boundary supplied to every dimensional scorer. */
public record ScoringContext(
        EvalExecutionEnvironment environment,
        ExecutionTrace trace,
        RunBudget budget) {

    public ScoringContext {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(budget, "budget");
    }
}
