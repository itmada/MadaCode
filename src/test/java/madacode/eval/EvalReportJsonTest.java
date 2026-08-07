package madacode.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.core.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReportJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void rendersCompleteMachineReadableResultTreeWithoutTruncatingDetails() throws Exception {
        String detail = "x".repeat(2_000);
        EvalCaseReport report = EvalCaseReport.of(List.of(new EvalResult(
                "case-1",
                "common",
                List.of("feature", "selftest"),
                EvalResult.FinalVerdict.PASS,
                EvalResult.HarnessStatus.OK,
                EvalResult.ExecutionStatus.COMPLETED,
                EvalResult.JudgeStatus.PASS,
                List.of(new DimensionScore(
                        Dimension.VERIFY,
                        EvalResult.JudgeStatus.PASS,
                        true,
                        "verify ok")),
                12,
                34,
                new RunMetrics(1, 2, 3, 4, new TokenUsage(10, 20, 30, 40)),
                "COMPLETED",
                detail,
                manifest())));

        Path providersFile = tempDir.resolve("providers.json");
        Files.writeString(providersFile, """
                {
                  "providers": [
                    {
                      "name": "provider-a",
                      "authToken": "token",
                      "baseUrl": "https://api.example.com",
                      "defaultModel": "model-b",
                      "pricing": {
                        "inputUsdPerMillion": 3.0,
                        "outputUsdPerMillion": 15.0
                      },
                      "models": [{ "name": "model-b" }]
                    }
                  ]
                }
                """);

        JsonNode root = mapper.readTree(EvalReportJson.render(
                List.of(report),
                EvalCostEstimator.fromProviderFile(providersFile)));

        assertEquals("1", root.path("schemaVersion").asText());
        assertEquals("COMPLETED", root.path("run").path("status").asText());
        assertEquals(1, root.path("run").path("plannedCases").asInt());
        assertEquals(1, root.path("run").path("completedCases").asInt());
        assertEquals(1, root.path("run").path("totalCases").asInt());
        assertEquals(1, root.path("run").path("casesPassed").asInt());
        assertEquals(1, root.path("run").path("stableCases").asInt());
        assertEquals(0, root.path("run").path("skippedCases").asInt());
        assertEquals(100, root.path("run").path("totalMetrics").path("tokenUsage").path("totalTokens").asInt());
        assertEquals(21, root.path("run").path("attemptPassRateWilson95").path("lowerPercent").asInt());
        assertTrue(root.path("run").path("costEstimate").path("totalUsd").asDouble() > 0.0);
        assertEquals("provider-a", root.path("environment").path("provider").asText());
        assertEquals("model-b", root.path("environment").path("model").asText());
        assertTrue(root.path("environment").path("dirtyWorktree").asBoolean());
        assertEquals("LOCAL_UNSAFE", root.path("environment").path("isolation").asText());

        JsonNode caseNode = root.path("cases").get(0);
        assertEquals("case-1", caseNode.path("id").asText());
        assertEquals("PASS", caseNode.path("gateVerdict").asText());
        assertEquals("PASS_AT_K", caseNode.path("passAtKVerdict").asText());
        assertEquals(1.0, caseNode.path("passRate").asDouble());
        assertEquals(21, caseNode.path("passRateWilson95").path("lowerPercent").asInt());
        assertTrue(caseNode.path("costEstimate").path("totalUsd").asDouble() > 0.0);
        assertEquals(46, caseNode.path("totalDurationMs").asLong());
        assertEquals("feature", caseNode.path("capabilities").get(0).asText());
        assertTrue(!caseNode.path("skipped").asBoolean());
        assertEquals("cases/case-1/case-report.json", caseNode.path("caseReportPath").asText());
        assertTrue(caseNode.path("attempts").isMissingNode());

        JsonNode caseReport = mapper.readTree(EvalReportJson.renderCase(
                report,
                EvalCostEstimator.fromProviderFile(providersFile)));
        assertEquals(1, caseReport.path("attempts").size());
        assertEquals("attempts/attempt-1/result.json",
                caseReport.path("attempts").get(0).path("resultPath").asText());
        assertEquals(1, caseReport.path("dimensions").size());

        JsonNode attemptNode = mapper.valueToTree(EvalReportJson.attemptJson(
                evalCase(), 1, report.attempts().getFirst()));
        assertEquals("1", attemptNode.path("schemaVersion").asText());
        assertEquals("PASS", attemptNode.path("verdict").asText());
        assertEquals("COMPLETED", attemptNode.path("executionStatus").asText());
        assertEquals(detail, attemptNode.path("executionDetail").asText());
        assertEquals(46, attemptNode.path("durationMs").asLong());
        assertEquals(4, attemptNode.path("metrics").path("toolCalls").asInt());
        assertEquals("VERIFY", attemptNode.path("dimensions").get(0).path("dimension").asText());
        assertTrue(attemptNode.path("dimensions").get(0).path("gating").asBoolean());
        assertTrue(attemptNode.path("artifacts").path("files").isEmpty());
    }

    @Test
    void omitsSkippedCasesFromRootReport() throws Exception {
        EvalCaseReport skipped = new EvalCaseReport(
                "case-skip",
                "common",
                List.of("feature"),
                List.of(),
                EvalCaseReport.SkipReason.BUDGET,
                "run token budget reached",
                manifest(),
                3);

        JsonNode root = mapper.readTree(EvalReportJson.render(List.of(skipped)));

        assertEquals(1, root.path("run").path("skippedCases").asInt());
        assertEquals(0, root.path("run").path("attemptTotal").asInt());
        assertTrue(root.path("cases").isEmpty());
    }

    @Test
    void hydratesCaseReportFromJsonDtoForResume() {
        EvalResult attempt = new EvalResult(
                "case-1",
                "common",
                List.of("feature"),
                EvalResult.FinalVerdict.PASS,
                EvalResult.HarnessStatus.OK,
                EvalResult.ExecutionStatus.COMPLETED,
                EvalResult.JudgeStatus.PASS,
                List.of(new DimensionScore(
                        Dimension.VERIFY,
                        EvalResult.JudgeStatus.PASS,
                        true,
                        "verify ok")),
                1,
                2,
                new RunMetrics(1, 0, 0, 2, new TokenUsage(3, 4, 0, 0)),
                "COMPLETED",
                "detail",
                manifest());
        EvalCaseReport original = EvalCaseReport.of(List.of(attempt));

        EvalReportJson.CaseReportJson caseReport;
        try {
            caseReport = mapper.readValue(
                    EvalReportJson.renderCase(original, EvalCostEstimator.none()),
                    EvalReportJson.CaseReportJson.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        EvalCaseReport hydrated = EvalReportJson.caseReport(
                caseReport,
                List.of(EvalReportJson.attemptJson(evalCase(), 1, attempt)));

        assertEquals(original.id(), hydrated.id());
        assertEquals(original.gateVerdict(), hydrated.gateVerdict());
        assertEquals(original.totalTokens(), hydrated.totalTokens());
        assertEquals(original.manifest().scorerFingerprint(), hydrated.manifest().scorerFingerprint());
        assertEquals("verify ok", hydrated.attempts().getFirst().dimensions().getFirst().detail());
    }

    private static EvalRunManifest manifest() {
        return new EvalRunManifest(
                Instant.parse("2026-06-19T00:00:00Z"),
                "casehash",
                "gitsha",
                true,
                "provider-a",
                "model-b",
                "runtime-fp",
                "scorer-fp",
                "LOCAL_UNSAFE",
                "HOST_READABLE",
                "ALLOWED",
                "ALLOWED",
                false,
                "21",
                "Mac OS X");
    }

    private static EvalCase evalCase() {
        return new EvalCase(
                "case-1", "desc", "common", "default", List.of("feature"), "run",
                false, 1, 1, 1, 1, 30, 30, 1024, null, EvalChecks.NONE, List.of());
    }
}
