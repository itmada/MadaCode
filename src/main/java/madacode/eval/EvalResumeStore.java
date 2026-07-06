package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads a previous run directory and exposes complete case-level results for resume. */
public final class EvalResumeStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path runDir;
    private final Map<String, EvalReportJson.CaseJson> casesById;

    private EvalResumeStore(Path runDir, Map<String, EvalReportJson.CaseJson> casesById) {
        this.runDir = runDir;
        this.casesById = Map.copyOf(casesById);
    }

    public static EvalResumeStore open(Path runDir) {
        Path normalized = runDir.toAbsolutePath().normalize();
        Path reportJson = normalized.resolve("report.json");
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("resume run directory not found: " + normalized);
        }
        if (!Files.isRegularFile(reportJson)) {
            throw new IllegalArgumentException("resume run directory has no report.json: " + normalized);
        }
        EvalReportJson.ReportJson report;
        try {
            report = MAPPER.readValue(reportJson.toFile(), EvalReportJson.ReportJson.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read resume report: " + reportJson, e);
        }
        if (!EvalReportJson.SCHEMA_VERSION.equals(report.schemaVersion())) {
            throw new IllegalArgumentException("resume report schemaVersion "
                    + report.schemaVersion()
                    + " is not supported; expected "
                    + EvalReportJson.SCHEMA_VERSION);
        }
        Map<String, EvalReportJson.CaseJson> cases = new LinkedHashMap<>();
        for (EvalReportJson.CaseJson evalCase : safeList(report.cases())) {
            EvalReportJson.CaseJson previous = cases.put(evalCase.id(), evalCase);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate case id in resume report: " + evalCase.id());
            }
        }
        return new EvalResumeStore(normalized, cases);
    }

    public Optional<EvalCaseReport> reusableCase(
            EvalCaseLoader.LoadedCase loaded,
            String scorerFingerprint) {
        EvalReportJson.CaseJson previous = casesById.get(loaded.evalCase().id());
        if (previous == null) {
            return Optional.empty();
        }
        validateCompatible(loaded, previous, scorerFingerprint);
        if (previous.skipped()) {
            return Optional.empty();
        }
        int expectedAttempts = loaded.evalCase().samplesOrDefault();
        if (safeList(previous.attempts()).size() != expectedAttempts) {
            return Optional.empty();
        }
        for (int attemptNumber = 1; attemptNumber <= expectedAttempts; attemptNumber++) {
            if (!hasReadableAttemptResult(loaded.evalCase().id(), attemptNumber)) {
                return Optional.empty();
            }
        }
        return Optional.of(EvalReportJson.caseReport(previous));
    }

    private void validateCompatible(
            EvalCaseLoader.LoadedCase loaded,
            EvalReportJson.CaseJson previous,
            String scorerFingerprint) {
        if (!loaded.caseHash().equals(previous.caseHash())) {
            throw new IllegalArgumentException("resume case " + loaded.evalCase().id()
                    + " has case hash " + previous.caseHash()
                    + " but current hash is " + loaded.caseHash());
        }
        EvalReportJson.ManifestJson manifest = previous.manifest();
        String previousScorer = manifest == null ? null : manifest.scorerFingerprint();
        if (!java.util.Objects.equals(scorerFingerprint, previousScorer)) {
            throw new IllegalArgumentException("resume case " + loaded.evalCase().id()
                    + " has scorer fingerprint " + previousScorer
                    + " but current fingerprint is " + scorerFingerprint);
        }
    }

    private boolean hasReadableAttemptResult(String caseId, int attemptNumber) {
        Path result = runDir.resolve(caseId)
                .resolve("attempt-" + attemptNumber)
                .resolve("result.json");
        if (!Files.isRegularFile(result)) {
            return false;
        }
        try {
            MAPPER.readValue(result.toFile(), EvalReportJson.AttemptJson.class);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
