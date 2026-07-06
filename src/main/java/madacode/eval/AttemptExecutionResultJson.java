package madacode.eval;

import madacode.services.api.ApiFailureClassification;

import java.util.List;

/**
 * Stable file-protocol DTO for container attempt execution.
 *
 * <p>This is deliberately separate from {@link ModeLauncher.LaunchOutcome}; the container
 * boundary must remain compatible even if launcher internals change.
 */
public record AttemptExecutionResultJson(
        String schemaVersion,
        String caseId,
        String mode,
        String executionStatus,
        String terminalSummary,
        String detail,
        String finalText,
        EvalReportJson.MetricsJson metrics,
        ApiFailureJson apiFailure,
        boolean quiescent,
        TraceJson trace,
        List<String> diagnostics) {

    public static final String SCHEMA_VERSION = "spike-1";

    public AttemptExecutionResultJson {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion;
        terminalSummary = terminalSummary == null ? "" : terminalSummary;
        detail = detail == null ? "" : detail;
        finalText = finalText == null ? "" : finalText;
        metrics = metrics == null ? EvalReportJson.metricsJson(RunMetrics.ZERO) : metrics;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public record ApiFailureJson(
            String type,
            boolean retryable,
            Integer httpStatus,
            String detail) {

        public static ApiFailureJson from(ApiFailureClassification failure) {
            if (failure == null) {
                return null;
            }
            return new ApiFailureJson(
                    failure.type().name(),
                    failure.retryable(),
                    failure.statusCode(),
                    failure.message());
        }
    }

    public record TraceJson(
            List<InvocationJson> invocations,
            List<TouchedFile> fileEffects,
            List<String> userTurns,
            List<String> assistantTurns,
            String finalText,
            EvalReportJson.MetricsJson metrics) {

        public TraceJson {
            invocations = invocations == null ? List.of() : List.copyOf(invocations);
            fileEffects = fileEffects == null ? List.of() : List.copyOf(fileEffects);
            userTurns = userTurns == null ? List.of() : List.copyOf(userTurns);
            assistantTurns = assistantTurns == null ? List.of() : List.copyOf(assistantTurns);
            finalText = finalText == null ? "" : finalText;
            metrics = metrics == null ? EvalReportJson.metricsJson(RunMetrics.ZERO) : metrics;
        }
    }

    public record InvocationJson(
            String name,
            String inputJson,
            String resultJson,
            List<madacode.core.model.ToolAccessEvidence> accessEvidence,
            String phase,
            int ordinal) {

        public InvocationJson {
            inputJson = inputJson == null ? "" : inputJson;
            resultJson = resultJson == null ? "" : resultJson;
            accessEvidence = accessEvidence == null ? List.of() : List.copyOf(accessEvidence);
        }
    }
}
