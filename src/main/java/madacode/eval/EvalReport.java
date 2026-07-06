package madacode.eval;

import madacode.core.model.TokenUsage;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Renders a list of {@link EvalCaseReport} into a Markdown report.
 *
 * <p>Because each case is sampled {@code N} times against a non-deterministic model, results
 * are reported at two granularities: a <b>case</b> passes under pass@k (any valid attempt
 * passed), and an <b>attempt</b> pass rate (k/N over valid attempts) shows stability.
 * Attempts that ended in an infrastructure error are excluded from rate denominators.
 */
public final class EvalReport {

    private EvalReport() {
    }

    public static String render(List<EvalCaseReport> reports) {
        return render(reports, EvalCostEstimator.none());
    }

    public static String render(List<EvalCaseReport> reports, EvalCostEstimator costEstimator) {
        EvalCostEstimator estimator = costEstimator == null ? EvalCostEstimator.none() : costEstimator;
        StringBuilder sb = new StringBuilder();
        sb.append("# MadaCode Eval Report\n\n");

        int totalCases = reports.size();
        long casesPassed = reports.stream().filter(EvalCaseReport::passed).count();
        long stableCases = reports.stream().filter(EvalCaseReport::stable).count();
        long skippedCases = reports.stream().filter(EvalCaseReport::skipped).count();
        long infraOnlyCases = reports.stream()
                .filter(r -> r.passAtKVerdict() == EvalCaseReport.PassAtKVerdict.INFRA_ERROR)
                .count();
        long measuredCases = totalCases - infraOnlyCases - skippedCases;
        long attemptPasses = reports.stream().mapToLong(EvalCaseReport::passes).sum();
        long attemptValid = reports.stream().mapToLong(EvalCaseReport::validAttempts).sum();
        long attemptTotal = reports.stream().mapToLong(r -> r.attempts().size()).sum();
        RunMetrics runMetrics = reports.stream()
                .map(EvalCaseReport::totalMetrics)
                .reduce(RunMetrics.ZERO, RunMetrics::plus);
        Optional<EvalCostEstimate> runCost = estimator.estimateReports(reports);

        sb.append("**").append(casesPassed).append('/').append(measuredCases)
                .append(" cases passed (pass@k, ")
                .append(percent(casesPassed, measuredCases)).append("%), ")
                .append(infraOnlyCases).append(" infra-only case(s), ")
                .append(skippedCases).append(" skipped case(s)**\n\n");
        sb.append("Attempt pass rate (k/N over valid attempts): **")
                .append(rateWithCi(attemptPasses, attemptValid)).append("** — ")
                .append(attemptTotal).append(" attempt(s) total, ")
                .append(attemptTotal - attemptValid).append(" infra error(s)\n\n");
        sb.append("Gate pass rate (all attempts pass, no infra errors): **")
                .append(stableCases).append('/').append(totalCases).append(" (")
                .append(percent(stableCases, totalCases)).append("%)**\n\n");
        sb.append("Run tokens: **").append(tokens(runMetrics.tokenUsage())).append(" total** ")
                .append(tokenBreakdown(runMetrics.tokenUsage()));
        runCost.ifPresent(cost -> sb.append(", estimated cost **")
                .append(cost(cost.totalUsd()))
                .append("**"));
        sb.append("\n\n");

        // Per-mode breakdown
        sb.append("## By mode\n\n");
        sb.append("| Mode | Gate | Cases (pass@k) | Attempts (k/N) |\n");
        sb.append("|------|------|----------------|----------------|\n");
        groupRates(reports, EvalCaseReport::mode).forEach((mode, rate) ->
                sb.append("| ").append(cell(mode)).append(" | ").append(rate.stable()).append(" | ")
                        .append(rate.cases()).append(" | ")
                        .append(rate.attempts()).append(" |\n"));
        sb.append('\n');

        // Per-capability breakdown (a case contributes to each of its tags)
        sb.append("## By capability\n\n");
        sb.append("| Capability | Gate | Cases (pass@k) | Attempts (k/N) |\n");
        sb.append("|------------|------|----------------|----------------|\n");
        Map<String, Rate> capRates = new TreeMap<>();
        for (EvalCaseReport r : reports) {
            for (String cap : r.capabilities()) {
                capRates.computeIfAbsent(cap, k -> new Rate()).add(r);
            }
        }
        if (capRates.isEmpty()) {
            sb.append("| _(none tagged)_ | - | - | - |\n");
        } else {
            capRates.forEach((cap, rate) ->
                    sb.append("| ").append(cell(cap)).append(" | ").append(rate.stable()).append(" | ")
                            .append(rate.cases()).append(" | ")
                            .append(rate.attempts()).append(" |\n"));
        }
        sb.append('\n');

        if (!reports.isEmpty()) {
            EvalRunManifest manifest = reports.getFirst().manifest();
            sb.append("## Run environment\n\n");
            sb.append("- Provider/model: `").append(manifest.provider()).append('/')
                    .append(manifest.model()).append("`\n");
            sb.append("- Isolation: `").append(manifest.isolation()).append("`\n");
            sb.append("- Trust boundary: judge=`").append(manifest.judgeVisibility())
                    .append("`, host=`").append(manifest.hostAccess())
                    .append("`, network=`").append(manifest.networkAccess())
                    .append("`, trustedMeasurement=`").append(manifest.trustedMeasurement())
                    .append("`\n");
            sb.append("- Runtime fingerprint: `").append(manifest.runtimeFingerprint()).append("`\n");
            sb.append("- Scorer fingerprint: `").append(manifest.scorerFingerprint()).append("`\n");
            sb.append("- Git commit: `").append(manifest.gitCommit()).append("`")
                    .append(manifest.dirtyWorktree() ? " (dirty)" : "").append('\n');
            sb.append("- Started: `").append(manifest.startedAt()).append("`\n");
            sb.append("- Java/OS: `").append(manifest.javaVersion()).append("` / `")
                    .append(manifest.os()).append("`\n\n");
            List<String> mismatches = manifestMismatches(reports, manifest);
            if (!mismatches.isEmpty()) {
                sb.append("> Warning: this report contains mixed run environments; the values above are from ")
                        .append(reports.getFirst().id()).append(". Differences: ")
                        .append(String.join(", ", mismatches)).append(".\n\n");
            }
        }

        // Per-case detail (one row per case; cost columns are summed across attempts)
        sb.append("## Cases\n\n");
        sb.append("| Case | Hash | Mode | Gate | Dimensions | pass@k verdict | Rate | Samples "
                + "| Ctrl it | Wrk it | Cycles | Tools | Tokens | Cost | Time |\n");
        sb.append("|------|------|------|------|------------|----------------|------|---------"
                + "|---------|--------|--------|-------|--------|------|------|\n");
        for (EvalCaseReport r : reports) {
            RunMetrics m = r.totalMetrics();
            Optional<EvalCostEstimate> caseCost = estimator.estimate(m, r.manifest());
            sb.append("| ").append(cell(r.id()))
                    .append(" | ").append(shortHash(r.manifest().caseHash()))
                    .append(" | ").append(cell(r.mode()))
                    .append(" | ").append(r.gateVerdict())
                    .append(" | ").append(dimensionSummary(r))
                    .append(" | ").append(r.passAtKVerdict())
                    .append(" | ").append(rateCell(r))
                    .append(" | ").append(r.samples())
                    .append(" | ").append(m.controlIterations())
                    .append(" | ").append(m.workerIterations())
                    .append(" | ").append(m.workerCycles())
                    .append(" | ").append(m.toolCalls())
                    .append(" | ").append(tokens(m.tokenUsage()))
                    .append(" | ").append(caseCost.map(c -> cost(c.totalUsd())).orElse("-"))
                    .append(" | ").append(r.totalDurationMs()).append("ms")
                    .append(" |\n");
        }
        sb.append('\n');

        boolean hasArtifacts = reports.stream()
                .flatMap(report -> report.attempts().stream())
                .anyMatch(attempt -> attempt.artifacts().present()
                        || !attempt.artifacts().warnings().isEmpty());
        if (hasArtifacts) {
            sb.append("## Attempt artifacts\n\n");
            sb.append("| Case | Attempt | Directory | Warnings |\n");
            sb.append("|------|---------|-----------|----------|\n");
            for (EvalCaseReport r : reports) {
                for (int i = 0; i < r.attempts().size(); i++) {
                    AttemptArtifacts artifacts = r.attempts().get(i).artifacts();
                    if (!artifacts.present() && artifacts.warnings().isEmpty()) {
                        continue;
                    }
                    sb.append("| ").append(cell(r.id()))
                            .append(" | ").append(i + 1).append('/').append(r.samples())
                            .append(" | ").append(cell(artifacts.directory()))
                            .append(" | ").append(cell(String.join("; ", artifacts.warnings())))
                            .append(" |\n");
                }
            }
            sb.append('\n');
        }

        // Failure/detail findings: include non-gating dimensional failures even when the
        // overall attempt passed, so optional measurements never disappear from the report.
        List<EvalCaseReport> imperfect = reports.stream()
                .filter(r -> r.gateVerdict() != EvalCaseReport.GateVerdict.PASS
                        || r.attempts().stream().anyMatch(EvalReport::hasDimensionFinding))
                .toList();
        if (!imperfect.isEmpty()) {
            sb.append("## Failures, flakes & dimension findings\n\n");
            for (EvalCaseReport r : imperfect) {
                sb.append("### ").append(r.id()).append(" — gate ").append(r.gateVerdict())
                        .append(", ").append(r.passAtKVerdict())
                        .append(" (").append(r.passes()).append('/').append(r.validAttempts())
                        .append(" passed)\n\n");
                if (r.skipped()) {
                    sb.append("Skipped (").append(r.skipReason().name().toLowerCase(Locale.ROOT))
                            .append("): ").append(cell(r.skipDetail())).append("\n\n");
                    continue;
                }
                int attempt = 0;
                for (EvalResult a : r.attempts()) {
                    attempt++;
                    if (a.passed() && !hasDimensionFinding(a)) {
                        continue;
                    }
                    sb.append("- attempt ").append(attempt).append('/').append(r.samples())
                            .append(": ").append(a.verdict())
                            .append(" (execution=").append(a.executionStatus())
                            .append(", judge=").append(a.judgeStatus()).append(")\n");
                    for (DimensionScore score : a.dimensions()) {
                        sb.append("  - ").append(score.dimension()).append(": ")
                                .append(score.status())
                                .append(score.gating() ? " (gating)" : "")
                                .append('\n');
                    }
                    sb.append("```\n").append(truncate(a.detail(), 1500)).append("\n```\n");
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    private static Map<String, Rate> groupRates(
            List<EvalCaseReport> reports, java.util.function.Function<EvalCaseReport, String> key) {
        Map<String, Rate> map = new TreeMap<>();
        for (EvalCaseReport r : reports) {
            map.computeIfAbsent(key.apply(r), k -> new Rate()).add(r);
        }
        return map;
    }

    private static long percent(long passed, long total) {
        return total == 0 ? 0 : (100 * passed / total);
    }

    private static String rateWithCi(long passed, long total) {
        WilsonInterval interval = WilsonInterval.of(passed, total);
        return passed + "/" + total
                + " (" + percent(passed, total) + "%, 95% CI "
                + interval.lowerPercentRounded() + "%-"
                + interval.upperPercentRounded() + "%)";
    }

    private static String rateCell(EvalCaseReport report) {
        if (report.skipped()) {
            return "SKIPPED (" + report.skipReason().name().toLowerCase(Locale.ROOT) + ")";
        }
        return rateWithCi(report.passes(), report.validAttempts());
    }

    private static int tokens(TokenUsage usage) {
        return usage == null ? 0 : usage.total();
    }

    private static String tokenBreakdown(TokenUsage usage) {
        TokenUsage safe = usage == null ? TokenUsage.ZERO : usage;
        return "(input=" + safe.inputTokens()
                + ", output=" + safe.outputTokens()
                + ", cacheCreate=" + safe.cacheCreationTokens()
                + ", cacheRead=" + safe.cacheReadTokens()
                + ")";
    }

    private static String cost(double value) {
        return String.format(Locale.ROOT, "$%.4f", value);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)";
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|")
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    private static String shortHash(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String dimensionSummary(EvalCaseReport report) {
        if (report.skipped()) {
            return "-";
        }
        java.util.Map<Dimension, java.util.Map<EvalResult.JudgeStatus, Long>> counts =
                new java.util.EnumMap<>(Dimension.class);
        for (EvalResult attempt : report.attempts()) {
            for (DimensionScore score : attempt.dimensions()) {
                counts.computeIfAbsent(
                                score.dimension(),
                                ignored -> new java.util.EnumMap<>(EvalResult.JudgeStatus.class))
                        .merge(score.status(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + statusCounts(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String statusCounts(java.util.Map<EvalResult.JudgeStatus, Long> counts) {
        return java.util.Arrays.stream(EvalResult.JudgeStatus.values())
                .filter(status -> counts.getOrDefault(status, 0L) > 0)
                .map(status -> status.name().charAt(0) + String.valueOf(counts.get(status)))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static boolean hasDimensionFinding(EvalResult attempt) {
        return attempt.dimensions().stream()
                .anyMatch(score -> score.status() != EvalResult.JudgeStatus.PASS);
    }

    private static List<String> manifestMismatches(
            List<EvalCaseReport> reports,
            EvalRunManifest expected) {
        java.util.Set<String> fields = new java.util.TreeSet<>();
        for (EvalCaseReport report : reports) {
            EvalRunManifest actual = report.manifest();
            if (!java.util.Objects.equals(expected.provider(), actual.provider())) {
                fields.add("provider");
            }
            if (!java.util.Objects.equals(expected.model(), actual.model())) {
                fields.add("model");
            }
            if (!java.util.Objects.equals(expected.runtimeFingerprint(), actual.runtimeFingerprint())) {
                fields.add("runtimeFingerprint");
            }
            if (!java.util.Objects.equals(expected.scorerFingerprint(), actual.scorerFingerprint())) {
                fields.add("scorerFingerprint");
            }
            if (!java.util.Objects.equals(expected.isolation(), actual.isolation())) {
                fields.add("isolation");
            }
            if (!java.util.Objects.equals(expected.judgeVisibility(), actual.judgeVisibility())) {
                fields.add("judgeVisibility");
            }
            if (!java.util.Objects.equals(expected.hostAccess(), actual.hostAccess())) {
                fields.add("hostAccess");
            }
            if (!java.util.Objects.equals(expected.networkAccess(), actual.networkAccess())) {
                fields.add("networkAccess");
            }
            if (expected.trustedMeasurement() != actual.trustedMeasurement()) {
                fields.add("trustedMeasurement");
            }
        }
        return List.copyOf(fields);
    }

    /** Accumulates case-level (pass@k) and attempt-level (k/N) tallies for a group. */
    private static final class Rate {
        long casesPassed;
        long casesMeasured;
        long casesStable;
        long casesTotal;
        long attemptPasses;
        long attemptValid;

        void add(EvalCaseReport report) {
            casesTotal++;
            if (report.stable()) {
                casesStable++;
            }
            if (report.passAtKVerdict() != EvalCaseReport.PassAtKVerdict.INFRA_ERROR
                    && report.passAtKVerdict() != EvalCaseReport.PassAtKVerdict.SKIPPED) {
                casesMeasured++;
                if (report.passed()) {
                    casesPassed++;
                }
            }
            attemptPasses += report.passes();
            attemptValid += report.validAttempts();
        }

        String cases() {
            return casesPassed + "/" + casesMeasured + " (" + percent(casesPassed, casesMeasured) + "%)";
        }

        String stable() {
            return casesStable + "/" + casesTotal + " (" + percent(casesStable, casesTotal) + "%)";
        }

        String attempts() {
            return attemptPasses + "/" + attemptValid + " (" + percent(attemptPasses, attemptValid) + "%)";
        }
    }
}
