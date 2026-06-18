package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;

import java.time.Duration;
import java.util.Objects;

/** Per-case dependencies supplied to a workflow launcher. */
public record EvalRunContext(HeadlessAgentRuntime runtime, RunBudget budget, long startedAtNanos) {

    public EvalRunContext(HeadlessAgentRuntime runtime, RunBudget budget) {
        this(runtime, budget, System.nanoTime());
    }

    public EvalRunContext {
        budget = Objects.requireNonNull(budget, "budget");
    }

    public Duration remainingTime() {
        long elapsed = System.nanoTime() - startedAtNanos;
        long remaining = budget.caseTimeout().toNanos() - elapsed;
        return Duration.ofNanos(Math.max(1, remaining));
    }
}
