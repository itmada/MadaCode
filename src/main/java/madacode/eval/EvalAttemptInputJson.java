package madacode.eval;

import java.util.List;

/** Closed input file-protocol DTO for a containerized eval attempt. */
public record EvalAttemptInputJson(
        String schemaVersion,
        EvalAttemptCaseJson evalCase,
        int attemptNumber,
        String executionMode,
        String projectDir,
        String providerConfigJson,
        List<String> diagnostics) {

    public static final String SCHEMA_VERSION = "1";
    public static final String MODE_NO_MODEL = "no-model";
    public static final String MODE_RUNTIME = "runtime";

    public EvalAttemptInputJson(
            String schemaVersion,
            EvalCase evalCase,
            int attemptNumber,
            String executionMode,
            String projectDir,
            String providerConfigJson,
            List<String> diagnostics) {
        this(
                schemaVersion,
                EvalAttemptCaseJson.from(evalCase),
                attemptNumber,
                executionMode,
                projectDir,
                providerConfigJson,
                diagnostics);
    }

    public EvalAttemptInputJson {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported attempt input schemaVersion " + schemaVersion);
        }
        if (evalCase == null) {
            throw new IllegalArgumentException("evalCase is required");
        }
        if (attemptNumber <= 0) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        executionMode = executionMode == null || executionMode.isBlank()
                ? MODE_NO_MODEL
                : executionMode;
        if (!MODE_NO_MODEL.equals(executionMode) && !MODE_RUNTIME.equals(executionMode)) {
            throw new IllegalArgumentException("executionMode must be no-model or runtime");
        }
        projectDir = projectDir == null || projectDir.isBlank() ? "/workspace" : projectDir;
        providerConfigJson = providerConfigJson == null ? "" : providerConfigJson;
        if (MODE_RUNTIME.equals(executionMode) && providerConfigJson.isBlank()) {
            throw new IllegalArgumentException("providerConfigJson is required for runtime attempts");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    EvalCase evalCaseDomain() {
        return evalCase.toEvalCase();
    }
}
