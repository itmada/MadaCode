package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReportCompareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @TempDir
    Path tempDir;

    @Test
    void detectsRegressionsImprovementsNewAndRemovedCases() {
        EvalReportCompare.Comparison comparison = EvalReportCompare.compare(
                report(List.of(
                        evalCase("gate-regressed", "PASS", "PASS_AT_K", 1.0, 2, 20, 200, 2000),
                        evalCase("rate-regressed", "FAIL", "PASS_AT_K", 0.75, 2, 10, 100, 1000),
                        evalCase("improved", "FAIL", "FAIL", 0.0, 1, 5, 50, 500),
                        evalCase("removed", "PASS", "PASS_AT_K", 1.0, 1, 1, 10, 100))),
                report(List.of(
                        evalCase("gate-regressed", "FAIL", "FAIL", 0.0, 2, 30, 100, 2500),
                        evalCase("rate-regressed", "FAIL", "PASS_AT_K", 0.25, 2, 8, 80, 900),
                        evalCase("improved", "PASS", "PASS_AT_K", 1.0, 1, 3, 30, 300),
                        evalCase("new", "PASS", "PASS_AT_K", 1.0, 1, 1, 10, 100))));

        assertTrue(comparison.hasGateRegression());
        assertEquals(List.of("new"), comparison.newCases());
        assertEquals(List.of("removed"), comparison.removedCases());
        assertEquals(2, comparison.regressions().size());
        assertEquals("gate-regressed", comparison.regressions().get(0).caseId());
        assertTrue(comparison.regressions().get(0).reasons().stream()
                .anyMatch(reason -> reason.contains("gate PASS -> FAIL")));
        assertTrue(comparison.regressions().get(1).reasons().stream()
                .anyMatch(reason -> reason.contains("k/N 25% < 75%")));
        assertEquals(1, comparison.improvements().size());
        assertEquals("improved", comparison.improvements().getFirst().caseId());
        assertEquals(3, comparison.metricDeltas().size());

        String markdown = EvalReportCompare.renderMarkdown(comparison);
        assertTrue(markdown.contains("Gate regression: **YES**"));
        assertTrue(markdown.contains("## Regressions"));
        assertTrue(markdown.contains("`gate-regressed`"));
        assertTrue(markdown.contains("## New cases"));
        assertTrue(markdown.contains("`new`"));
        assertTrue(markdown.contains("## Metric changes"));
        assertTrue(markdown.contains("+50%"));
    }

    @Test
    void passAtKRegressionDoesNotCountAsGateRegressionWhenGateWasAlreadyFailing() {
        EvalReportCompare.Comparison comparison = EvalReportCompare.compare(
                report(List.of(evalCase("case", "FAIL", "PASS_AT_K", 0.5, 2, 1, 1, 1))),
                report(List.of(evalCase("case", "FAIL", "FAIL", 0.0, 2, 1, 1, 1))));

        assertFalse(comparison.hasGateRegression());
        assertEquals(1, comparison.regressions().size());
        assertTrue(comparison.regressions().getFirst().reasons().stream()
                .anyMatch(reason -> reason.contains("pass@k PASS_AT_K -> FAIL")));
    }

    @Test
    void skippedCandidateIsStableNonPassRegression() {
        EvalReportCompare.Comparison comparison = EvalReportCompare.compare(
                report(List.of(evalCase("case", "PASS", "PASS_AT_K", 1.0, 1, 1, 1, 1))),
                report(List.of(evalCase("case", "SKIPPED", "SKIPPED", 0.0, 3, 0, 0, 0))));

        assertTrue(comparison.hasGateRegression());
        assertEquals(1, comparison.regressions().size());
        assertTrue(comparison.regressions().getFirst().reasons().stream()
                .anyMatch(reason -> reason.contains("gate PASS -> SKIPPED")));
    }


    @Test
    void compareReadsJsonFilesAndRejectsSchemaMismatches() throws Exception {
        Path baseline = tempDir.resolve("baseline.json");
        Path candidate = tempDir.resolve("candidate.json");
        Files.writeString(baseline, MAPPER.writeValueAsString(report(List.of(
                evalCase("case", "PASS", "PASS_AT_K", 1.0, 1, 1, 1, 1)))));
        Files.writeString(candidate, MAPPER.writeValueAsString(report(List.of(
                evalCase("case", "PASS", "PASS_AT_K", 1.0, 1, 1, 1, 1)))));

        EvalReportCompare.Comparison comparison = EvalReportCompare.compare(baseline, candidate);

        assertFalse(comparison.hasGateRegression());

        EvalReportJson.ReportJson badSchema = new EvalReportJson.ReportJson(
                "999",
                null,
                null,
                List.of());
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> EvalReportCompare.compare(badSchema, report(List.of())));
        assertTrue(error.getMessage().contains("schemaVersion 999"));
    }

    private static EvalReportJson.ReportJson report(List<EvalReportJson.CaseJson> cases) {
        return new EvalReportJson.ReportJson(
                EvalReportJson.SCHEMA_VERSION,
                null,
                null,
                cases);
    }

    private static EvalReportJson.CaseJson evalCase(
            String id,
            String gateVerdict,
            String passAtKVerdict,
            double passRate,
            int samples,
            int totalTools,
            int totalTokens,
            int totalDurationMs) {
        return new EvalReportJson.CaseJson(
                id,
                "common",
                List.of("tag"),
                "hash-" + id,
                samples,
                samples,
                0,
                Math.round(passRate * samples),
                passRate,
                new EvalReportJson.WilsonIntervalJson(0, samples, 0.0, 0.0, 0, 0),
                passAtKVerdict,
                gateVerdict,
                "PASS".equals(gateVerdict),
                "PASS_AT_K".equals(passAtKVerdict),
                "SKIPPED".equals(gateVerdict),
                "SKIPPED".equals(gateVerdict) ? "BUDGET" : null,
                "SKIPPED".equals(gateVerdict) ? "budget" : null,
                new EvalReportJson.MetricsJson(
                        0,
                        0,
                        0,
                        0,
                        totalTools,
                        new EvalReportJson.TokenUsageJson(0, totalTokens, 0, 0, totalTokens)),
                null,
                totalDurationMs,
                "cases/" + id + "/case-report.json");
    }
}
