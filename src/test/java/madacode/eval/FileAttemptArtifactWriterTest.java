package madacode.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.core.model.ToolAccessEvidence;
import madacode.core.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAttemptArtifactWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesAttemptArtifactsAndTruncatesLargeToolResults() throws Exception {
        FileAttemptArtifactWriter writer = new FileAttemptArtifactWriter(tempDir);
        String largeResult = "x".repeat(300_000);
        ExecutionTrace trace = new ExecutionTrace(
                List.of(new ToolInvocation("bash", "{\"cmd\":\"echo\"}", largeResult,
                        List.of(new ToolAccessEvidence(
                                tempDir.resolve("secret.key").toString(),
                                ToolAccessEvidence.EvidenceSource.RESOLVED_PATH,
                                false)),
                        ToolInvocation.Phase.CONTROL, 0)),
                List.of(new TouchedFile("src/App.java", TouchedFile.ChangeKind.MODIFIED)),
                List.of("fix it"),
                List.of("done"),
                "done",
                new RunMetrics(1, 0, 0, 1, new TokenUsage(1, 2, 3, 4)));
        EvalExecutionEnvironment.VerifyOutcome verifyOutcome =
                new EvalExecutionEnvironment.VerifyOutcome(
                        EvalExecutionEnvironment.VerifyStatus.FAILED,
                        1,
                        "assertion failed");

        AttemptArtifacts artifacts = writer.write(
                evalCase(),
                2,
                new AttemptEvidence(trace, verifyOutcome),
                result());

        assertEquals("cases/case-1/attempts/attempt-2", artifacts.directory());
        assertTrue(artifacts.warnings().isEmpty());
        Path attemptDir = tempDir.resolve("cases/case-1/attempts/attempt-2");
        assertTrue(Files.isRegularFile(attemptDir.resolve("trace.json")));
        assertTrue(Files.isRegularFile(attemptDir.resolve("verify.txt")));
        assertTrue(Files.isRegularFile(attemptDir.resolve("result.json")));

        JsonNode traceJson = MAPPER.readTree(attemptDir.resolve("trace.json").toFile());
        JsonNode invocation = traceJson.path("invocations").get(0);
        assertTrue(invocation.path("resultTruncated").asBoolean());
        assertEquals(300_000, invocation.path("originalResultChars").asInt());
        assertTrue(invocation.path("resultJson").asText().length() < 300_000);
        assertEquals("RESOLVED_PATH", invocation.path("accessEvidence").get(0).path("source").asText());
        assertEquals("src/App.java", traceJson.path("fileEffects").get(0).path("relPath").asText());
        assertTrue(traceJson.path("metrics").isMissingNode());

        String verifyText = Files.readString(attemptDir.resolve("verify.txt"));
        assertTrue(verifyText.contains("status=FAILED"));
        assertTrue(verifyText.contains("exitCode=1"));
        assertTrue(verifyText.contains("assertion failed"));

        JsonNode resultJson = MAPPER.readTree(attemptDir.resolve("result.json").toFile());
        assertEquals("1", resultJson.path("schemaVersion").asText());
        assertEquals("PASS", resultJson.path("verdict").asText());
        assertEquals("cases/case-1/attempts/attempt-2",
                resultJson.path("artifacts").path("directory").asText());
        assertFalse(resultJson.path("artifacts").path("files").isEmpty());
    }

    private static EvalCase evalCase() {
        return new EvalCase(
                "case-1",
                "desc",
                "common",
                "default",
                List.of("selftest"),
                "run",
                false,
                1,
                1,
                1,
                1,
                30,
                30,
                1024,
                null,
                EvalChecks.NONE,
                List.of());
    }

    private static EvalResult result() {
        return new EvalResult(
                "case-1",
                "common",
                List.of("selftest"),
                EvalResult.FinalVerdict.PASS,
                EvalResult.HarnessStatus.OK,
                EvalResult.ExecutionStatus.COMPLETED,
                EvalResult.JudgeStatus.PASS,
                List.of(new DimensionScore(Dimension.VERIFY, EvalResult.JudgeStatus.PASS, true, "ok")),
                10,
                20,
                new RunMetrics(1, 0, 0, 1, TokenUsage.ZERO),
                "COMPLETED",
                "detail",
                manifest());
    }

    private static EvalRunManifest manifest() {
        return new EvalRunManifest(
                Instant.parse("2026-06-19T00:00:00Z"),
                "hash",
                "git",
                false,
                "provider",
                "model",
                "runtime",
                "scorer",
                "LOCAL_UNSAFE",
                "HOST_READABLE",
                "ALLOWED",
                "ALLOWED",
                false,
                "21",
                "Mac OS X");
    }
}
