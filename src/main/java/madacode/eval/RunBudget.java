package madacode.eval;

import java.time.Duration;

/**
 * Immutable resource limits for one eval case. Every workflow phase derives its limits
 * from this object so cost and termination policy are declared once rather than scattered
 * across launchers.
 */
public record RunBudget(
        int maxIterations,
        int maxWorkerCycles,
        int maxWorkerIterations,
        Duration caseTimeout,
        Duration verifyTimeout,
        int maxProcessOutputBytes) {

    public RunBudget {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (maxWorkerCycles <= 0) {
            throw new IllegalArgumentException("maxWorkerCycles must be positive");
        }
        if (maxWorkerIterations <= 0) {
            throw new IllegalArgumentException("maxWorkerIterations must be positive");
        }
        if (caseTimeout == null || caseTimeout.isZero() || caseTimeout.isNegative()) {
            throw new IllegalArgumentException("caseTimeout must be positive");
        }
        if (verifyTimeout == null || verifyTimeout.isZero() || verifyTimeout.isNegative()) {
            throw new IllegalArgumentException("verifyTimeout must be positive");
        }
        if (maxProcessOutputBytes <= 0) {
            throw new IllegalArgumentException("maxProcessOutputBytes must be positive");
        }
    }

    public static RunBudget from(EvalCase evalCase) {
        return new RunBudget(
                evalCase.maxIterationsOrDefault(),
                evalCase.maxCyclesOrDefault(),
                evalCase.workerMaxIterationsOrDefault(),
                Duration.ofSeconds(evalCase.timeoutSecondsOrDefault()),
                Duration.ofSeconds(evalCase.verifyTimeoutSecondsOrDefault()),
                evalCase.maxProcessOutputBytesOrDefault());
    }
}
