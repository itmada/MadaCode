package madacode.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import madacode.core.model.TokenUsage;

import java.time.Instant;
import java.util.List;

/** Machine-readable eval report renderer. */
public final class EvalReportJson {

    public static final String SCHEMA_VERSION = "1";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportJson() {
    }

    public static String render(List<EvalCaseReport> reports) {
        return render(reports, EvalCostEstimator.none());
    }

    public static String render(List<EvalCaseReport> reports, EvalCostEstimator costEstimator) {
        return render(reports, costEstimator, EvalRunProgress.completed(reports.size()));
    }

    public static String render(
            List<EvalCaseReport> reports,
            EvalCostEstimator costEstimator,
            EvalRunProgress progress) {
        try {
            return MAPPER.writeValueAsString(from(reports, costEstimator, progress)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render eval report JSON", e);
        }
    }

    static ReportJson from(List<EvalCaseReport> reports) {
        return from(reports, EvalCostEstimator.none());
    }

    static ReportJson from(List<EvalCaseReport> reports, EvalCostEstimator costEstimator) {
        return from(reports, costEstimator, EvalRunProgress.completed(reports.size()));
    }

    static ReportJson from(
            List<EvalCaseReport> reports,
            EvalCostEstimator costEstimator,
            EvalRunProgress progress) {
        EvalRunManifest manifest = reports.isEmpty() ? null : reports.getFirst().manifest();
        EvalCostEstimator estimator = costEstimator == null ? EvalCostEstimator.none() : costEstimator;
        return new ReportJson(
                SCHEMA_VERSION,
                runSummary(reports, manifest, estimator, progress),
                manifest == null ? null : environmentJson(manifest),
                reports.stream()
                        .filter(report -> !report.skipped())
                        .map(report -> caseJson(report, estimator))
                        .toList());
    }

    public static String renderCase(EvalCaseReport report, EvalCostEstimator costEstimator) {
        try {
            return MAPPER.writeValueAsString(caseReportJson(report, costEstimator)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render eval case report JSON", e);
        }
    }

    static CaseReportJson caseReportJson(
            EvalCaseReport report,
            EvalCostEstimator costEstimator) {
        EvalCostEstimator estimator = costEstimator == null ? EvalCostEstimator.none() : costEstimator;
        return new CaseReportJson(
                SCHEMA_VERSION,
                caseJson(report, estimator),
                environmentJson(report.manifest()),
                dimensionAggregates(report),
                attemptSummaries(report));
    }

    public static boolean supportsSchemaVersion(String schemaVersion) {
        return SCHEMA_VERSION.equals(schemaVersion);
    }

    private static RunSummaryJson runSummary(
            List<EvalCaseReport> reports,
            EvalRunManifest manifest,
            EvalCostEstimator estimator,
            EvalRunProgress progress) {
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
                manifestMismatches(reports, manifest),
                progress.status().name(),
                progress.plannedCases(),
                progress.completedCases(),
                progress.startedAt().toString(),
                progress.updatedAt().toString(),
                progress.currentCaseId(),
                progress.abortDetail());
    }

    private static CaseJson caseJson(EvalCaseReport report, EvalCostEstimator estimator) {
        return new CaseJson(
                report.id(),
                report.mode(),
                report.capabilities(),
                report.manifest().caseHash(),
                report.manifest().caseRepository(),
                report.manifest().caseBaseCommit(),
                report.manifest().workspaceProtocol(),
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
                "cases/" + report.id() + "/case-report.json");
    }

    static AttemptJson attemptJson(EvalCase evalCase, int attemptNumber, EvalResult attempt) {
        return attemptJson(evalCase.id(), attemptNumber, attempt);
    }

    static AttemptJson attemptJson(String caseId, int attemptNumber, EvalResult attempt) {
        return new AttemptJson(
                SCHEMA_VERSION,
                caseId,
                attemptNumber,
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
                attempt.manifest().startedAt() == null ? null : attempt.manifest().startedAt().toString(),
                artifactsJson(attempt.artifacts()));
    }

    private static List<AttemptSummaryJson> attemptSummaries(EvalCaseReport report) {
        List<AttemptSummaryJson> summaries = new java.util.ArrayList<>();
        for (int i = 0; i < report.attempts().size(); i++) {
            EvalResult attempt = report.attempts().get(i);
            int number = i + 1;
            String base = "attempts/attempt-" + number + "/";
            summaries.add(new AttemptSummaryJson(
                    number,
                    attempt.verdict().name(),
                    attempt.executionStatus().name(),
                    attempt.judgeStatus().name(),
                    attempt.durationMs(),
                    metricsJson(attempt.metrics()),
                    base + "result.json",
                    base + "trace.json",
                    base + "verify.txt"));
        }
        return List.copyOf(summaries);
    }

    private static List<DimensionAggregateJson> dimensionAggregates(EvalCaseReport report) {
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
                .map(entry -> new DimensionAggregateJson(
                        entry.getKey().name(),
                        entry.getValue().getOrDefault(EvalResult.JudgeStatus.PASS, 0L),
                        entry.getValue().getOrDefault(EvalResult.JudgeStatus.FAIL, 0L),
                        entry.getValue().getOrDefault(EvalResult.JudgeStatus.ERROR, 0L),
                        entry.getValue().getOrDefault(EvalResult.JudgeStatus.NOT_RUN, 0L)))
                .toList();
    }

    static EvalCaseReport caseReport(
            CaseReportJson caseReport,
            List<AttemptJson> attempts) {
        CaseJson evalCase = caseReport.evalCase();
        if (evalCase.skipped()) {
            return new EvalCaseReport(
                    evalCase.id(),
                    evalCase.mode(),
                    evalCase.capabilities(),
                    List.of(),
                    EvalCaseReport.SkipReason.valueOf(evalCase.skipReason()),
                    evalCase.skipDetail(),
                    evalRunManifest(
                            caseReport.environment(),
                            evalCase,
                            null),
                    evalCase.samples());
        }
        return new EvalCaseReport(
                evalCase.id(),
                evalCase.mode(),
                evalCase.capabilities(),
                safeList(attempts).stream()
                        .map(attempt -> evalResult(caseReport, attempt))
                        .toList());
    }

    private static EvalResult evalResult(CaseReportJson caseReport, AttemptJson attempt) {
        CaseJson evalCase = caseReport.evalCase();
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
                attempt.executionDetail(),
                evalRunManifest(
                        caseReport.environment(),
                        evalCase,
                        attempt.startedAt()),
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

    private static EnvironmentJson environmentJson(EvalRunManifest manifest) {
        return new EnvironmentJson(
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
                manifest.agent(),
                manifest.javaVersion(),
                manifest.os(),
                manifest.caseRepository(),
                manifest.caseBaseCommit(),
                manifest.workspaceProtocol());
    }

    private static EvalRunManifest evalRunManifest(
            EnvironmentJson environment,
            CaseJson evalCase,
            String startedAt) {
        if (environment == null) {
            return null;
        }
        return new EvalRunManifest(
                startedAt == null ? null : Instant.parse(startedAt),
                evalCase.caseHash(),
                environment.gitCommit(),
                environment.dirtyWorktree(),
                environment.provider(),
                environment.model(),
                environment.runtimeFingerprint(),
                environment.scorerFingerprint(),
                environment.isolation(),
                environment.judgeVisibility(),
                environment.hostAccess(),
                environment.networkAccess(),
                environment.trustedMeasurement(),
                environment.executionBackend(),
                environment.containerImage(),
                environment.containerImageDigest(),
                environment.resourceLimits(),
                environment.networkPolicy(),
                environment.providerConfigMaterialization(),
                environment.projectExtensionMounts(),
                environment.agent(),
                environment.javaVersion(),
                environment.os(),
                evalCase.repository(),
                evalCase.baseCommit(),
                evalCase.workspaceProtocol());
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
            EnvironmentJson environment,
            List<CaseJson> cases) {
    }

    public record CaseReportJson(
            String schemaVersion,
            CaseJson evalCase,
            EnvironmentJson environment,
            List<DimensionAggregateJson> dimensions,
            List<AttemptSummaryJson> attempts) {
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
            List<String> manifestMismatches,
            String status,
            int plannedCases,
            int completedCases,
            String startedAt,
            String updatedAt,
            String currentCaseId,
            String abortDetail) {
    }

    public record CaseJson(
            String id,
            String mode,
            List<String> capabilities,
            String caseHash,
            String repository,
            String baseCommit,
            String workspaceProtocol,
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
            String caseReportPath) {

        /** Compatibility constructor for report fixtures without Git source metadata. */
        public CaseJson(
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
                String caseReportPath) {
            this(
                    id,
                    mode,
                    capabilities,
                    caseHash,
                    "",
                    "",
                    "workspace-copy",
                    samples,
                    validAttempts,
                    infraErrors,
                    passes,
                    passRate,
                    passRateWilson95,
                    passAtKVerdict,
                    gateVerdict,
                    stable,
                    passed,
                    skipped,
                    skipReason,
                    skipDetail,
                    totalMetrics,
                    costEstimate,
                    totalDurationMs,
                    caseReportPath);
        }
    }

    public record AttemptJson(
            String schemaVersion,
            String caseId,
            int attemptNumber,
            String verdict,
            String harnessStatus,
            String executionStatus,
            String judgeStatus,
            long executionDurationMs,
            long judgeDurationMs,
            long durationMs,
            MetricsJson metrics,
            String terminalSummary,
            String executionDetail,
            List<DimensionJson> dimensions,
            String startedAt,
            AttemptArtifactsJson artifacts) {
    }

    public record AttemptSummaryJson(
            int number,
            String verdict,
            String executionStatus,
            String judgeStatus,
            long durationMs,
            MetricsJson metrics,
            String resultPath,
            String tracePath,
            String verifyPath) {
    }

    public record DimensionAggregateJson(
            String dimension,
            long pass,
            long fail,
            long error,
            long notRun) {
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

    public record EnvironmentJson(
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
            String agent,
            String javaVersion,
            String os,
            String caseRepository,
            String caseBaseCommit,
            String workspaceProtocol) {
    }

}
