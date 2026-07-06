package madacode.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import madacode.core.model.TokenUsage;

import java.time.Instant;
import java.util.List;

/** Machine-readable eval report renderer. */
public final class EvalReportJson {

    public static final String SCHEMA_VERSION = "3";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportJson() {
    }

    public static String render(List<EvalCaseReport> reports) {
        return render(reports, EvalCostEstimator.none());
    }

    public static String render(List<EvalCaseReport> reports, EvalCostEstimator costEstimator) {
        try {
            return MAPPER.writeValueAsString(from(reports, costEstimator)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render eval report JSON", e);
        }
    }

    static ReportJson from(List<EvalCaseReport> reports) {
        return from(reports, EvalCostEstimator.none());
    }

    static ReportJson from(List<EvalCaseReport> reports, EvalCostEstimator costEstimator) {
        EvalRunManifest manifest = reports.isEmpty() ? null : reports.getFirst().manifest();
        EvalCostEstimator estimator = costEstimator == null ? EvalCostEstimator.none() : costEstimator;
        return new ReportJson(
                SCHEMA_VERSION,
                runSummary(reports, manifest, estimator),
                manifest == null ? null : manifestJson(manifest),
                reports.stream().map(report -> caseJson(report, estimator)).toList());
    }

    private static RunSummaryJson runSummary(
            List<EvalCaseReport> reports,
            EvalRunManifest manifest,
            EvalCostEstimator estimator) {
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
        RunMetrics totalMetrics = reports.stream()
                .map(EvalCaseReport::totalMetrics)
                .reduce(RunMetrics.ZERO, RunMetrics::plus);

        return new RunSummaryJson(
                totalCases,
                measuredCases,
                casesPassed,
                stableCases,
                infraOnlyCases,
                skippedCases,
                attemptTotal,
                attemptValid,
                attemptPasses,
                attemptTotal - attemptValid,
                wilsonJson(WilsonInterval.of(attemptPasses, attemptValid)),
                metricsJson(totalMetrics),
                estimator.estimateReports(reports)
                        .map(EvalReportJson::costEstimateJson)
                        .orElse(null),
                manifestMismatches(reports, manifest));
    }

    private static CaseJson caseJson(EvalCaseReport report, EvalCostEstimator estimator) {
        return new CaseJson(
                report.id(),
                report.mode(),
                report.capabilities(),
                report.manifest().caseHash(),
                report.samples(),
                report.validAttempts(),
                report.infraErrors(),
                report.passes(),
                report.passRate(),
                wilsonJson(report.passRateWilson95()),
                report.passAtKVerdict().name(),
                report.gateVerdict().name(),
                report.stable(),
                report.passed(),
                report.skipped(),
                report.skipReason() == null ? null : report.skipReason().name(),
                report.skipDetail().isBlank() ? null : report.skipDetail(),
                metricsJson(report.totalMetrics()),
                estimator.estimate(report.totalMetrics(), report.manifest())
                        .map(EvalReportJson::costEstimateJson)
                        .orElse(null),
                report.totalDurationMs(),
                manifestJson(report.manifest()),
                report.attempts().stream().map(EvalReportJson::attemptJson).toList());
    }

    static AttemptJson attemptJson(EvalResult attempt) {
        return new AttemptJson(
                attempt.verdict().name(),
                attempt.harnessStatus().name(),
                attempt.executionStatus().name(),
                attempt.judgeStatus().name(),
                attempt.executionDurationMs(),
                attempt.judgeDurationMs(),
                attempt.durationMs(),
                metricsJson(attempt.metrics()),
                attempt.terminalSummary(),
                attempt.detail(),
                attempt.dimensions().stream().map(EvalReportJson::dimensionJson).toList(),
                manifestJson(attempt.manifest()),
                artifactsJson(attempt.artifacts()));
    }

    static EvalCaseReport caseReport(CaseJson evalCase) {
        if (evalCase.skipped()) {
            return new EvalCaseReport(
                    evalCase.id(),
                    evalCase.mode(),
                    evalCase.capabilities(),
                    List.of(),
                    EvalCaseReport.SkipReason.valueOf(evalCase.skipReason()),
                    evalCase.skipDetail(),
                    evalRunManifest(evalCase.manifest()),
                    evalCase.samples());
        }
        return new EvalCaseReport(
                evalCase.id(),
                evalCase.mode(),
                evalCase.capabilities(),
                safeList(evalCase.attempts()).stream()
                        .map(attempt -> evalResult(evalCase, attempt))
                        .toList());
    }

    private static EvalResult evalResult(CaseJson evalCase, AttemptJson attempt) {
        return new EvalResult(
                evalCase.id(),
                evalCase.mode(),
                evalCase.capabilities(),
                EvalResult.FinalVerdict.valueOf(attempt.verdict()),
                EvalResult.HarnessStatus.valueOf(attempt.harnessStatus()),
                EvalResult.ExecutionStatus.valueOf(attempt.executionStatus()),
                EvalResult.JudgeStatus.valueOf(attempt.judgeStatus()),
                safeList(attempt.dimensions()).stream()
                        .map(EvalReportJson::dimensionScore)
                        .toList(),
                attempt.executionDurationMs(),
                attempt.judgeDurationMs(),
                runMetrics(attempt.metrics()),
                attempt.terminalSummary(),
                attempt.detail(),
                evalRunManifest(attempt.manifest()),
                attemptArtifacts(attempt.artifacts()));
    }

    private static DimensionScore dimensionScore(DimensionJson score) {
        return new DimensionScore(
                Dimension.valueOf(score.dimension()),
                EvalResult.JudgeStatus.valueOf(score.status()),
                score.gating(),
                score.detail());
    }

    private static DimensionJson dimensionJson(DimensionScore score) {
        return new DimensionJson(
                score.dimension().name(),
                score.status().name(),
                score.gating(),
                score.detail());
    }

    static MetricsJson metricsJson(RunMetrics metrics) {
        RunMetrics safe = metrics == null ? RunMetrics.ZERO : metrics;
        return new MetricsJson(
                safe.controlIterations(),
                safe.workerIterations(),
                safe.totalIterations(),
                safe.workerCycles(),
                safe.toolCalls(),
                tokenUsageJson(safe.tokenUsage()));
    }

    static RunMetrics runMetrics(MetricsJson metrics) {
        if (metrics == null) {
            return RunMetrics.ZERO;
        }
        return new RunMetrics(
                metrics.controlIterations(),
                metrics.workerIterations(),
                metrics.workerCycles(),
                metrics.toolCalls(),
                tokenUsage(metrics.tokenUsage()));
    }

    private static TokenUsageJson tokenUsageJson(TokenUsage usage) {
        TokenUsage safe = usage == null ? TokenUsage.ZERO : usage;
        return new TokenUsageJson(
                safe.inputTokens(),
                safe.outputTokens(),
                safe.cacheCreationTokens(),
                safe.cacheReadTokens(),
                safe.total());
    }

    private static TokenUsage tokenUsage(TokenUsageJson usage) {
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        return new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheCreationTokens(),
                usage.cacheReadTokens());
    }

    private static WilsonIntervalJson wilsonJson(WilsonInterval interval) {
        return new WilsonIntervalJson(
                interval.successes(),
                interval.total(),
                interval.lower(),
                interval.upper(),
                interval.lowerPercentRounded(),
                interval.upperPercentRounded());
    }

    private static CostEstimateJson costEstimateJson(EvalCostEstimate estimate) {
        return new CostEstimateJson(
                tokenUsageJson(estimate.tokenUsage()),
                estimate.inputUsd(),
                estimate.outputUsd(),
                estimate.totalUsd());
    }

    static ManifestJson manifestJson(EvalRunManifest manifest) {
        if (manifest == null) {
            return null;
        }
        return new ManifestJson(
                manifest.startedAt() == null ? null : manifest.startedAt().toString(),
                manifest.caseHash(),
                manifest.gitCommit(),
                manifest.dirtyWorktree(),
                manifest.provider(),
                manifest.model(),
                manifest.runtimeFingerprint(),
                manifest.scorerFingerprint(),
                manifest.isolation(),
                manifest.judgeVisibility(),
                manifest.hostAccess(),
                manifest.networkAccess(),
                manifest.trustedMeasurement(),
                manifest.executionBackend(),
                manifest.containerImage(),
                manifest.containerImageDigest(),
                manifest.resourceLimits(),
                manifest.networkPolicy(),
                manifest.providerConfigMaterialization(),
                manifest.projectExtensionMounts(),
                manifest.javaVersion(),
                manifest.os());
    }

    private static EvalRunManifest evalRunManifest(ManifestJson manifest) {
        if (manifest == null) {
            return null;
        }
        return new EvalRunManifest(
                manifest.startedAt() == null ? null : Instant.parse(manifest.startedAt()),
                manifest.caseHash(),
                manifest.gitCommit(),
                manifest.dirtyWorktree(),
                manifest.provider(),
                manifest.model(),
                manifest.runtimeFingerprint(),
                manifest.scorerFingerprint(),
                manifest.isolation(),
                manifest.judgeVisibility(),
                manifest.hostAccess(),
                manifest.networkAccess(),
                manifest.trustedMeasurement(),
                manifest.executionBackend(),
                manifest.containerImage(),
                manifest.containerImageDigest(),
                manifest.resourceLimits(),
                manifest.networkPolicy(),
                manifest.providerConfigMaterialization(),
                manifest.projectExtensionMounts(),
                manifest.javaVersion(),
                manifest.os());
    }

    private static AttemptArtifactsJson artifactsJson(AttemptArtifacts artifacts) {
        AttemptArtifacts safe = artifacts == null ? AttemptArtifacts.NONE : artifacts;
        return new AttemptArtifactsJson(
                safe.directory(),
                safe.files(),
                safe.warnings());
    }

    private static AttemptArtifacts attemptArtifacts(AttemptArtifactsJson artifacts) {
        if (artifacts == null) {
            return AttemptArtifacts.NONE;
        }
        return new AttemptArtifacts(
                artifacts.directory(),
                artifacts.files(),
                artifacts.warnings());
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> manifestMismatches(
            List<EvalCaseReport> reports,
            EvalRunManifest expected) {
        if (expected == null) {
            return List.of();
        }
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
            if (!java.util.Objects.equals(expected.executionBackend(), actual.executionBackend())) {
                fields.add("executionBackend");
            }
            if (!java.util.Objects.equals(expected.networkPolicy(), actual.networkPolicy())) {
                fields.add("networkPolicy");
            }
        }
        return List.copyOf(fields);
    }

    public record ReportJson(
            String schemaVersion,
            RunSummaryJson run,
            ManifestJson manifest,
            List<CaseJson> cases) {
    }

    public record RunSummaryJson(
            int totalCases,
            long measuredCases,
            long casesPassed,
            long stableCases,
            long infraOnlyCases,
            long skippedCases,
            long attemptTotal,
            long attemptValid,
            long attemptPasses,
            long attemptInfraErrors,
            WilsonIntervalJson attemptPassRateWilson95,
            MetricsJson totalMetrics,
            CostEstimateJson costEstimate,
            List<String> manifestMismatches) {
    }

    public record CaseJson(
            String id,
            String mode,
            List<String> capabilities,
            String caseHash,
            int samples,
            long validAttempts,
            long infraErrors,
            long passes,
            double passRate,
            WilsonIntervalJson passRateWilson95,
            String passAtKVerdict,
            String gateVerdict,
            boolean stable,
            boolean passed,
            boolean skipped,
            String skipReason,
            String skipDetail,
            MetricsJson totalMetrics,
            CostEstimateJson costEstimate,
            long totalDurationMs,
            ManifestJson manifest,
            List<AttemptJson> attempts) {
    }

    public record AttemptJson(
            String verdict,
            String harnessStatus,
            String executionStatus,
            String judgeStatus,
            long executionDurationMs,
            long judgeDurationMs,
            long durationMs,
            MetricsJson metrics,
            String terminalSummary,
            String detail,
            List<DimensionJson> dimensions,
            ManifestJson manifest,
            AttemptArtifactsJson artifacts) {
    }

    public record DimensionJson(
            String dimension,
            String status,
            boolean gating,
            String detail) {
    }

    public record MetricsJson(
            int controlIterations,
            int workerIterations,
            int totalIterations,
            int workerCycles,
            int toolCalls,
            TokenUsageJson tokenUsage) {
    }

    public record TokenUsageJson(
            int inputTokens,
            int outputTokens,
            int cacheCreationTokens,
            int cacheReadTokens,
            int totalTokens) {
    }

    public record WilsonIntervalJson(
            long successes,
            long total,
            double lower,
            double upper,
            int lowerPercent,
            int upperPercent) {
    }

    public record CostEstimateJson(
            TokenUsageJson tokenUsage,
            double inputUsd,
            double outputUsd,
            double totalUsd) {
    }

    public record AttemptArtifactsJson(
            String directory,
            List<String> files,
            List<String> warnings) {
    }

    public record ManifestJson(
            String startedAt,
            String caseHash,
            String gitCommit,
            boolean dirtyWorktree,
            String provider,
            String model,
            String runtimeFingerprint,
            String scorerFingerprint,
            String isolation,
            String judgeVisibility,
            String hostAccess,
            String networkAccess,
            boolean trustedMeasurement,
            String executionBackend,
            String containerImage,
            String containerImageDigest,
            String resourceLimits,
            String networkPolicy,
            String providerConfigMaterialization,
            List<String> projectExtensionMounts,
            String javaVersion,
            String os) {
    }
}
