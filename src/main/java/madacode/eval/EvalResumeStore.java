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
    private final Map<String, EvalReportJson.CaseReportJson> casesById;

    private EvalResumeStore(Path runDir, Map<String, EvalReportJson.CaseReportJson> casesById) {
        this.runDir = runDir;
        this.casesById = Map.copyOf(casesById);
    }

    public static EvalResumeStore open(Path runDir) {
        Path normalized = runDir.toAbsolutePath().normalize();
        Path reportJson = normalized.resolve("report.json");
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("resume run directory not found: " + normalized);
        }
        boolean rootReadable = false;
        if (Files.isRegularFile(reportJson)) {
            try {
                EvalReportJson.ReportJson report = MAPPER.readValue(
                        reportJson.toFile(), EvalReportJson.ReportJson.class);
                requireSupportedSchema(report.schemaVersion(), "resume report");
                rootReadable = true;
            } catch (IOException e) {
                // Complete case checkpoints are sufficient to rebuild the root report.
            }
        }
        Map<String, EvalReportJson.CaseReportJson> cases = new LinkedHashMap<>();
        loadCaseCheckpoints(normalized, cases);
        if (!rootReadable && cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "resume run directory has no readable v1 report checkpoints: " + normalized);
        }
        return new EvalResumeStore(normalized, cases);
    }

    private static void loadCaseCheckpoints(
            Path runDir,
            Map<String, EvalReportJson.CaseReportJson> cases) {
        Path casesDir = runDir.resolve("cases");
        if (!Files.isDirectory(casesDir)) {
            return;
        }
        try (var entries = Files.list(casesDir)) {
            for (Path entry : entries.filter(Files::isDirectory).toList()) {
                Path checkpoint = entry.resolve("case-report.json");
                if (!Files.isRegularFile(checkpoint)) {
                    continue;
                }
                EvalReportJson.CaseReportJson caseReport = MAPPER.readValue(
                        checkpoint.toFile(), EvalReportJson.CaseReportJson.class);
                requireSupportedSchema(caseReport.schemaVersion(), "case checkpoint " + checkpoint);
                EvalReportJson.CaseJson evalCase = caseReport.evalCase();
                if (evalCase == null) {
                    throw new IllegalArgumentException("case checkpoint has no evalCase: " + checkpoint);
                }
                EvalReportJson.CaseReportJson previous = cases.put(evalCase.id(), caseReport);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate case id in checkpoints: " + evalCase.id());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read case checkpoints under " + casesDir, e);
        }
    }

    private static void requireSupportedSchema(String schemaVersion, String source) {
        if (!EvalReportJson.supportsSchemaVersion(schemaVersion)) {
            throw new IllegalArgumentException(source + " schemaVersion "
                    + schemaVersion
                    + " is not supported; expected "
                    + EvalReportJson.SCHEMA_VERSION);
        }
    }

    public Optional<EvalCaseReport> reusableCase(
            EvalCaseLoader.LoadedCase loaded,
            String scorerFingerprint) {
        EvalReportJson.CaseReportJson checkpoint = casesById.get(loaded.evalCase().id());
        if (checkpoint == null) {
            return Optional.empty();
        }
        EvalReportJson.CaseJson previous = checkpoint.evalCase();
        validateCompatible(loaded, previous, scorerFingerprint);
        if (previous.skipped()) {
            return Optional.empty();
        }
        int expectedAttempts = loaded.evalCase().samplesOrDefault();
        if (safeList(checkpoint.attempts()).size() != expectedAttempts) {
            return Optional.empty();
        }
        List<EvalReportJson.AttemptJson> attempts = new java.util.ArrayList<>(expectedAttempts);
        for (int i = 0; i < checkpoint.attempts().size(); i++) {
            int expectedNumber = i + 1;
            EvalReportJson.AttemptSummaryJson summary = checkpoint.attempts().get(i);
            if (summary.number() != expectedNumber) {
                return Optional.empty();
            }
            Optional<EvalReportJson.AttemptJson> attempt = readAttemptResult(
                    loaded.evalCase().id(), summary);
            if (attempt.isEmpty()
                    || attempt.get().attemptNumber() != expectedNumber
                    || !loaded.evalCase().id().equals(attempt.get().caseId())) {
                return Optional.empty();
            }
            attempts.add(attempt.get());
        }
        return Optional.of(EvalReportJson.caseReport(checkpoint, attempts));
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
        EvalReportJson.CaseReportJson checkpoint = casesById.get(loaded.evalCase().id());
        EvalReportJson.EnvironmentJson environment = checkpoint == null ? null : checkpoint.environment();
        String previousScorer = environment == null ? null : environment.scorerFingerprint();
        if (!java.util.Objects.equals(scorerFingerprint, previousScorer)) {
            throw new IllegalArgumentException("resume case " + loaded.evalCase().id()
                    + " has scorer fingerprint " + previousScorer
                    + " but current fingerprint is " + scorerFingerprint);
        }
    }

    private Optional<EvalReportJson.AttemptJson> readAttemptResult(
            String caseId,
            EvalReportJson.AttemptSummaryJson summary) {
        Path caseDir = runDir.resolve("cases").resolve(caseId).normalize();
        Path result = caseDir.resolve(summary.resultPath()).normalize();
        if (!result.startsWith(caseDir)) {
            throw new IllegalArgumentException("attempt result escapes case directory: " + summary.resultPath());
        }
        if (!Files.isRegularFile(result)) {
            return Optional.empty();
        }
        try {
            EvalReportJson.AttemptJson attempt = MAPPER.readValue(
                    result.toFile(), EvalReportJson.AttemptJson.class);
            requireSupportedSchema(attempt.schemaVersion(), "attempt result " + result);
            return Optional.of(attempt);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
