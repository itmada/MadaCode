package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import madacode.core.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalResumeStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @TempDir
    Path tempDir;

    @Test
    void reusesCompleteCompatibleCaseFromReportJsonAndAttemptArtifacts() throws Exception {
        Path runDir = writeRunDir(2);
        EvalResumeStore store = EvalResumeStore.open(runDir);

        EvalCaseReport resumed = store.reusableCase(loadedCase("casehash", 2), "scorer-fp")
                .orElseThrow();

        assertEquals("case-1", resumed.id());
        assertEquals(2, resumed.attempts().size());
        assertEquals(EvalCaseReport.GateVerdict.PASS, resumed.gateVerdict());
    }

    @Test
    void incompleteAttemptArtifactsCauseCaseToRerun() throws Exception {
        Path runDir = writeRunDir(2);
        Files.delete(runDir.resolve("case-1/attempt-2/result.json"));
        EvalResumeStore store = EvalResumeStore.open(runDir);

        assertTrue(store.reusableCase(loadedCase("casehash", 2), "scorer-fp").isEmpty());
    }

    @Test
    void rejectsCaseHashOrScorerFingerprintMismatch() throws Exception {
        Path runDir = writeRunDir(1);
        EvalResumeStore store = EvalResumeStore.open(runDir);

        assertThrows(IllegalArgumentException.class,
                () -> store.reusableCase(loadedCase("changed", 1), "scorer-fp"));
        assertThrows(IllegalArgumentException.class,
                () -> store.reusableCase(loadedCase("casehash", 1), "other-scorer"));
    }

    private Path writeRunDir(int attempts) throws Exception {
        Path runDir = tempDir.resolve("run");
        Files.createDirectories(runDir);
        EvalCaseReport report = EvalCaseReport.of(java.util.stream.IntStream.rangeClosed(1, attempts)
                .mapToObj(this::result)
                .toList());
        Files.writeString(runDir.resolve("report.json"), EvalReportJson.render(List.of(report)));
        for (int i = 1; i <= attempts; i++) {
            Path attemptDir = runDir.resolve("case-1/attempt-" + i);
            Files.createDirectories(attemptDir);
            Files.writeString(
                    attemptDir.resolve("result.json"),
                    MAPPER.writeValueAsString(EvalReportJson.attemptJson(report.attempts().get(i - 1))));
        }
        return runDir;
    }

    private EvalCaseLoader.LoadedCase loadedCase(String caseHash, int samples) {
        return new EvalCaseLoader.LoadedCase(
                new EvalCase(
                        "case-1",
                        "desc",
                        "common",
                        "default",
                        List.of("feature"),
                        "run",
                        false,
                        samples,
                        1,
                        1,
                        1,
                        30,
                        30,
                        1024,
                        null,
                        EvalChecks.NONE,
                        List.of()),
                tempDir.resolve("case-1"),
                caseHash);
    }

    private EvalResult result(int attempt) {
        return new EvalResult(
                "case-1",
                "common",
                List.of("feature"),
                EvalResult.FinalVerdict.PASS,
                EvalResult.HarnessStatus.OK,
                EvalResult.ExecutionStatus.COMPLETED,
                EvalResult.JudgeStatus.PASS,
                List.of(new DimensionScore(Dimension.VERIFY, EvalResult.JudgeStatus.PASS, true, "ok")),
                attempt,
                attempt,
                new RunMetrics(0, 0, 0, 0, new TokenUsage(0, attempt, 0, 0)),
                "COMPLETED",
                "detail",
                manifest());
    }

    private EvalRunManifest manifest() {
        return new EvalRunManifest(
                Instant.parse("2026-06-19T00:00:00Z"),
                "casehash",
                "git",
                false,
                "provider",
                "model",
                "runtime",
                "scorer-fp",
                "LOCAL_UNSAFE",
                "HOST_READABLE",
                "ALLOWED",
                "ALLOWED",
                false,
                "21",
                "Mac OS X");
    }
}
