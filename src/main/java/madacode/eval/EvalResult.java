package madacode.eval;

import madacode.core.model.TokenUsage;

import java.util.List;

/**
 * The judged result of one eval case — the per-case row of the result set.
 *
 * <p>{@code passed} comes from the {@link Scorer} (verify.sh). The rest are observed
 * metrics captured from the real run: model iterations, worker cycles,
 * tool calls, cumulative token usage, phase durations, and a
 * short {@code terminalSummary} of how the run ended.
 */
public record EvalResult(
        String id,
        String mode,
        List<String> capabilities,
        FinalVerdict verdict,
        HarnessStatus harnessStatus,
        ExecutionStatus executionStatus,
        JudgeStatus judgeStatus,
        List<DimensionScore> dimensions,
        long executionDurationMs,
        long judgeDurationMs,
        RunMetrics metrics,
        String terminalSummary,
        String detail,
        EvalRunManifest manifest,
        AttemptArtifacts artifacts) {

    public EvalResult(
            String id,
            String mode,
            List<String> capabilities,
            FinalVerdict verdict,
            HarnessStatus harnessStatus,
            ExecutionStatus executionStatus,
            JudgeStatus judgeStatus,
            List<DimensionScore> dimensions,
            long executionDurationMs,
            long judgeDurationMs,
            RunMetrics metrics,
            String terminalSummary,
            String detail,
            EvalRunManifest manifest) {
        this(id, mode, capabilities, verdict, harnessStatus, executionStatus, judgeStatus,
                dimensions, executionDurationMs, judgeDurationMs, metrics, terminalSummary,
                detail, manifest, AttemptArtifacts.NONE);
    }

    public EvalResult {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        artifacts = artifacts == null ? AttemptArtifacts.NONE : artifacts;
    }

    public EvalResult withArtifacts(AttemptArtifacts artifacts) {
        return new EvalResult(
                id,
                mode,
                capabilities,
                verdict,
                harnessStatus,
                executionStatus,
                judgeStatus,
                dimensions,
                executionDurationMs,
                judgeDurationMs,
                metrics,
                terminalSummary,
                detail,
                manifest,
                artifacts);
    }

    public boolean passed() {
        return verdict == FinalVerdict.PASS;
    }

    public int controlIterations() {
        return metrics == null ? 0 : metrics.controlIterations();
    }

    public int workerIterations() {
        return metrics == null ? 0 : metrics.workerIterations();
    }

    public int workerCycles() {
        return metrics == null ? 0 : metrics.workerCycles();
    }

    public int toolCalls() {
        return metrics == null ? 0 : metrics.toolCalls();
    }

    public TokenUsage tokenUsage() {
        return metrics == null ? TokenUsage.ZERO : metrics.tokenUsage();
    }

    public long durationMs() {
        return executionDurationMs + judgeDurationMs;
    }

    public enum FinalVerdict {
        PASS,
        FAIL,
        INFRA_ERROR
    }

    public enum HarnessStatus {
        OK,
        INTERNAL_ERROR
    }

    public enum ExecutionStatus {
        COMPLETED,
        MAX_ITERATIONS,
        API_ERROR,
        MODEL_TRUNCATED,
        PERMISSION_DENIED,
        CANCELLED,
        TIMED_OUT,
        WORKFLOW_FAILED,
        CRASHED
    }

    public enum JudgeStatus {
        PASS,
        FAIL,
        ERROR,
        NOT_RUN
    }
}
