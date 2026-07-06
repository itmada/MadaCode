package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Compares two machine-readable eval reports. */
public final class EvalReportCompare {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalReportCompare() {
    }

    public static Comparison compare(Path baselinePath, Path candidatePath) {
        return compare(read(baselinePath), read(candidatePath));
    }

    static Comparison compare(
            EvalReportJson.ReportJson baseline,
            EvalReportJson.ReportJson candidate) {
        requireSchemaVersion(baseline, "baseline");
        requireSchemaVersion(candidate, "candidate");

        Map<String, EvalReportJson.CaseJson> baselineCases = byId(baseline.cases());
        Map<String, EvalReportJson.CaseJson> candidateCases = byId(candidate.cases());

        List<CaseDelta> regressions = new ArrayList<>();
        List<CaseDelta> improvements = new ArrayList<>();
        List<String> newCases = new ArrayList<>();
        List<String> removedCases = new ArrayList<>();
        List<MetricDelta> metricDeltas = new ArrayList<>();
        boolean gateRegression = false;

        for (String id : unionIds(baselineCases, candidateCases)) {
            EvalReportJson.CaseJson base = baselineCases.get(id);
            EvalReportJson.CaseJson cand = candidateCases.get(id);
            if (base == null) {
                newCases.add(id);
                continue;
            }
            if (cand == null) {
                removedCases.add(id);
                continue;
            }

            List<String> regressionReasons = regressionReasons(base, cand);
            if (!regressionReasons.isEmpty()) {
                regressions.add(new CaseDelta(id, regressionReasons));
                if (isPass(base.gateVerdict()) && !isPass(cand.gateVerdict())) {
                    gateRegression = true;
                }
            }

            List<String> improvementReasons = improvementReasons(base, cand);
            if (!improvementReasons.isEmpty()) {
                improvements.add(new CaseDelta(id, improvementReasons));
            }

            metricDeltas.add(metricDelta(id, base, cand));
        }

        return new Comparison(
                regressions,
                improvements,
                List.copyOf(newCases),
                List.copyOf(removedCases),
                metricDeltas.stream()
                        .sorted(Comparator.comparing(MetricDelta::caseId))
                        .toList(),
                gateRegression);
    }

    public static String renderMarkdown(Comparison comparison) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MadaCode Eval Compare\n\n");
        sb.append("Gate regression: **")
                .append(comparison.hasGateRegression() ? "YES" : "no")
                .append("**\n\n");

        renderCaseDeltas(sb, "Regressions", comparison.regressions());
        renderCaseDeltas(sb, "Improvements", comparison.improvements());
        renderCaseList(sb, "New cases", comparison.newCases());
        renderCaseList(sb, "Removed cases", comparison.removedCases());
        renderMetricDeltas(sb, comparison.metricDeltas());
        return sb.toString();
    }

    private static EvalReportJson.ReportJson read(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), EvalReportJson.ReportJson.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read eval report JSON: " + path, e);
        }
    }

    private static void requireSchemaVersion(EvalReportJson.ReportJson report, String label) {
        if (!Objects.equals(EvalReportJson.SCHEMA_VERSION, report.schemaVersion())) {
            throw new IllegalArgumentException(label + " schemaVersion "
                    + report.schemaVersion()
                    + " is not supported; expected "
                    + EvalReportJson.SCHEMA_VERSION);
        }
    }

    private static Map<String, EvalReportJson.CaseJson> byId(List<EvalReportJson.CaseJson> cases) {
        Map<String, EvalReportJson.CaseJson> byId = new TreeMap<>();
        for (EvalReportJson.CaseJson evalCase : cases == null ? List.<EvalReportJson.CaseJson>of() : cases) {
            EvalReportJson.CaseJson previous = byId.put(evalCase.id(), evalCase);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate case id in report JSON: " + evalCase.id());
            }
        }
        return byId;
    }

    private static List<String> unionIds(
            Map<String, EvalReportJson.CaseJson> baseline,
            Map<String, EvalReportJson.CaseJson> candidate) {
        java.util.SortedSet<String> ids = new java.util.TreeSet<>();
        ids.addAll(baseline.keySet());
        ids.addAll(candidate.keySet());
        return List.copyOf(ids);
    }

    private static List<String> regressionReasons(
            EvalReportJson.CaseJson baseline,
            EvalReportJson.CaseJson candidate) {
        List<String> reasons = new ArrayList<>();
        if (isPass(baseline.gateVerdict()) && !isPass(candidate.gateVerdict())) {
            reasons.add("gate " + baseline.gateVerdict() + " -> " + candidate.gateVerdict());
        }
        if (passAtKRank(candidate.passAtKVerdict()) < passAtKRank(baseline.passAtKVerdict())) {
            reasons.add("pass@k " + baseline.passAtKVerdict() + " -> " + candidate.passAtKVerdict());
        }
        if (candidate.passRate() < baseline.passRate()) {
            reasons.add("k/N " + percent(candidate.passRate())
                    + " < " + percent(baseline.passRate()));
        }
        return reasons;
    }

    private static List<String> improvementReasons(
            EvalReportJson.CaseJson baseline,
            EvalReportJson.CaseJson candidate) {
        List<String> reasons = new ArrayList<>();
        if (!isPass(baseline.gateVerdict()) && isPass(candidate.gateVerdict())) {
            reasons.add("gate " + baseline.gateVerdict() + " -> " + candidate.gateVerdict());
        }
        if (passAtKRank(candidate.passAtKVerdict()) > passAtKRank(baseline.passAtKVerdict())) {
            reasons.add("pass@k " + baseline.passAtKVerdict() + " -> " + candidate.passAtKVerdict());
        }
        if (candidate.passRate() > baseline.passRate()) {
            reasons.add("k/N " + percent(candidate.passRate())
                    + " > " + percent(baseline.passRate()));
        }
        return reasons;
    }

    private static boolean isPass(String gateVerdict) {
        return "PASS".equals(gateVerdict);
    }

    private static int passAtKRank(String passAtKVerdict) {
        return switch (passAtKVerdict == null ? "" : passAtKVerdict) {
            case "PASS_AT_K" -> 2;
            case "FAIL" -> 1;
            case "INFRA_ERROR", "SKIPPED" -> 0;
            default -> -1;
        };
    }

    private static MetricDelta metricDelta(
            String id,
            EvalReportJson.CaseJson baseline,
            EvalReportJson.CaseJson candidate) {
        return new MetricDelta(
                id,
                metricChange(
                        averageToolCalls(baseline),
                        averageToolCalls(candidate)),
                metricChange(
                        averageTokens(baseline),
                        averageTokens(candidate)),
                metricChange(
                        averageDurationMs(baseline),
                        averageDurationMs(candidate)));
    }

    private static double averageToolCalls(EvalReportJson.CaseJson evalCase) {
        EvalReportJson.MetricsJson metrics = evalCase.totalMetrics();
        return evalCase.samples() == 0
                ? 0.0
                : (double) (metrics == null ? 0 : metrics.toolCalls()) / evalCase.samples();
    }

    private static double averageTokens(EvalReportJson.CaseJson evalCase) {
        EvalReportJson.MetricsJson metrics = evalCase.totalMetrics();
        EvalReportJson.TokenUsageJson tokenUsage = metrics == null ? null : metrics.tokenUsage();
        return evalCase.samples() == 0
                ? 0.0
                : (double) (tokenUsage == null ? 0 : tokenUsage.totalTokens()) / evalCase.samples();
    }

    private static double averageDurationMs(EvalReportJson.CaseJson evalCase) {
        return evalCase.samples() == 0
                ? 0.0
                : (double) evalCase.totalDurationMs() / evalCase.samples();
    }

    private static MetricChange metricChange(double baseline, double candidate) {
        Double percentChange = baseline == 0.0
                ? null
                : ((candidate - baseline) / baseline) * 100.0;
        return new MetricChange(baseline, candidate, percentChange);
    }

    private static void renderCaseDeltas(
            StringBuilder sb,
            String title,
            List<CaseDelta> deltas) {
        sb.append("## ").append(title).append("\n\n");
        if (deltas.isEmpty()) {
            sb.append("- none\n\n");
            return;
        }
        for (CaseDelta delta : deltas) {
            sb.append("- `").append(delta.caseId()).append("`: ")
                    .append(String.join("; ", delta.reasons()))
                    .append('\n');
        }
        sb.append('\n');
    }

    private static void renderCaseList(StringBuilder sb, String title, List<String> cases) {
        sb.append("## ").append(title).append("\n\n");
        if (cases.isEmpty()) {
            sb.append("- none\n\n");
            return;
        }
        for (String id : cases) {
            sb.append("- `").append(id).append("`\n");
        }
        sb.append('\n');
    }

    private static void renderMetricDeltas(StringBuilder sb, List<MetricDelta> deltas) {
        sb.append("## Metric changes\n\n");
        if (deltas.isEmpty()) {
            sb.append("- none\n");
            return;
        }
        sb.append("| Case | Avg tools | Avg tokens | Avg duration |\n");
        sb.append("|------|-----------|------------|--------------|\n");
        for (MetricDelta delta : deltas) {
            sb.append("| `").append(delta.caseId()).append("` | ")
                    .append(format(delta.toolCalls()))
                    .append(" | ")
                    .append(format(delta.tokens()))
                    .append(" | ")
                    .append(format(delta.durationMs()))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private static String format(MetricChange change) {
        return number(change.baseline())
                + " -> "
                + number(change.candidate())
                + " ("
                + (change.percentChange() == null ? "n/a" : signedPercent(change.percentChange()))
                + ")";
    }

    private static String percent(double rate) {
        return Math.round(rate * 100.0) + "%";
    }

    private static String signedPercent(double value) {
        return (value > 0 ? "+" : "") + Math.round(value) + "%";
    }

    private static String number(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    public record Comparison(
            List<CaseDelta> regressions,
            List<CaseDelta> improvements,
            List<String> newCases,
            List<String> removedCases,
            List<MetricDelta> metricDeltas,
            boolean hasGateRegression) {
    }

    public record CaseDelta(String caseId, List<String> reasons) {
        public CaseDelta {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record MetricDelta(
            String caseId,
            MetricChange toolCalls,
            MetricChange tokens,
            MetricChange durationMs) {
    }

    public record MetricChange(
            double baseline,
            double candidate,
            Double percentChange) {
    }
}
