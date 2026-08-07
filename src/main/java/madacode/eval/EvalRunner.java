package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;
import madacode.governance.IsolationProfile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The single, generic eval driver. For every case it runs {@code samples} independent
 * attempts (a real model is non-deterministic, so one attempt is a high-variance binary)
 * and aggregates them into an {@link EvalCaseReport} carrying pass@k and the k/N pass rate.
 *
 * <p>Each attempt: creates an {@link EvalExecutionEnvironment}, creates an environment-rooted
 * session with the case's permission mode, resolves the {@link ModeLauncher} for the case's
 * mode, launches the <em>real</em> agent pipeline under a layered timeout, then scores the
 * result with the {@link Scorer}.
 *
 * <p>Capabilities never become bespoke code paths here — they are case data routed through
 * this one loop, which is what makes the framework extensible by adding launchers and cases
 * rather than rewriting the runner.
 */
public final class EvalRunner {

    private final HeadlessAgentRuntime runtime;
    private final ScorerPipeline scorers;
    private final AttemptExecutor attemptExecutor;
    private final AttemptArtifactWriter artifactWriter;
    private final EvalAgent agent;

    public EvalRunner(HeadlessAgentRuntime runtime, ModeLauncherRegistry launchers, Scorer scorer) {
        this(runtime, launchers, ScorerPipeline.of(scorer), Sandbox::of);
    }

    public EvalRunner(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            ScorerPipeline scorers) {
        this(runtime, launchers, scorers, Sandbox::of);
    }

    public EvalRunner(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            Scorer scorer,
            EvalExecutionEnvironmentFactory environments) {
        this(runtime, launchers, ScorerPipeline.of(scorer), environments);
    }

    public EvalRunner(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            ScorerPipeline scorers,
            EvalExecutionEnvironmentFactory environments) {
        this(runtime, launchers, scorers, environments, AttemptArtifactWriter.NOOP);
    }

    public EvalRunner(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            ScorerPipeline scorers,
            EvalExecutionEnvironmentFactory environments,
            AttemptArtifactWriter artifactWriter) {
        this(runtime, launchers, scorers, environments, artifactWriter, EvalAgent.MADACODE);
    }

    public EvalRunner(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            ScorerPipeline scorers,
            EvalExecutionEnvironmentFactory environments,
            AttemptArtifactWriter artifactWriter,
            EvalAgent agent) {
        this.runtime = runtime;
        this.scorers = Objects.requireNonNull(scorers, "scorers");
        Objects.requireNonNull(launchers, "launchers");
        Objects.requireNonNull(environments, "environments");
        this.agent = agent == null ? EvalAgent.MADACODE : agent;
        this.attemptExecutor = new LocalAttemptExecutor(
                runtime,
                launchers,
                environments,
                this.scorers.reproducibilityFingerprint(),
                this.agent);
        this.artifactWriter = artifactWriter == null ? AttemptArtifactWriter.NOOP : artifactWriter;
    }

    public EvalRunner(
            HeadlessAgentRuntime runtime,
            ScorerPipeline scorers,
            AttemptExecutor attemptExecutor,
            AttemptArtifactWriter artifactWriter) {
        this.runtime = runtime;
        this.scorers = Objects.requireNonNull(scorers, "scorers");
        this.attemptExecutor = Objects.requireNonNull(attemptExecutor, "attemptExecutor");
        this.artifactWriter = artifactWriter == null ? AttemptArtifactWriter.NOOP : artifactWriter;
        this.agent = EvalAgent.MADACODE;
    }

    public List<EvalCaseReport> runAll(List<EvalCaseLoader.LoadedCase> cases) {
        return runAll(cases, EvalRunLimit.NONE);
    }

    public List<EvalCaseReport> runAll(List<EvalCaseLoader.LoadedCase> cases, EvalRunLimit limit) {
        return runAll(cases, limit, 1);
    }

    public List<EvalCaseReport> runAll(
            List<EvalCaseLoader.LoadedCase> cases,
            EvalRunLimit limit,
            int concurrency) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        List<EvalCaseReport> reports = new ArrayList<>();
        RunMetrics accumulated = RunMetrics.ZERO;
        EvalRunLimit effectiveLimit = limit == null ? EvalRunLimit.NONE : limit;
        for (EvalCaseLoader.LoadedCase loaded : cases) {
            if (effectiveLimit.shouldSkipNextCase(accumulated)) {
                reports.add(skippedCase(
                        loaded,
                        EvalCaseReport.SkipReason.BUDGET,
                        effectiveLimit.skipDetail(accumulated)));
                continue;
            }
            EvalCaseReport report = runCase(loaded, concurrency);
            reports.add(report);
            accumulated = accumulated.plus(report.totalMetrics());
        }
        return reports;
    }

    /** Runs every sample for one case and aggregates them into a case-level report. */
    public EvalCaseReport runCase(EvalCaseLoader.LoadedCase loaded) {
        return runCase(loaded, 1);
    }

    public EvalCaseReport runCase(EvalCaseLoader.LoadedCase loaded, int concurrency) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        int samples = loaded.evalCase().samplesOrDefault();
        if (concurrency <= 1 || samples <= 1) {
            return runCaseSequential(loaded, samples);
        }
        return runCaseParallel(loaded, samples, concurrency);
    }

    private EvalCaseReport runCaseSequential(EvalCaseLoader.LoadedCase loaded, int samples) {
        List<EvalResult> attempts = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            attempts.add(run(loaded, i + 1));
        }
        return EvalCaseReport.of(attempts);
    }

    private EvalCaseReport runCaseParallel(
            EvalCaseLoader.LoadedCase loaded,
            int samples,
            int concurrency) {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mada-eval-attempt-", 0).factory());
        List<Future<EvalResult>> futures = new ArrayList<>(samples);
        try {
            java.util.concurrent.Semaphore semaphore =
                    new java.util.concurrent.Semaphore(Math.min(concurrency, samples));
            for (int i = 0; i < samples; i++) {
                int attemptNumber = i + 1;
                futures.add(executor.submit(() -> {
                    semaphore.acquire();
                    try {
                        return run(loaded, attemptNumber);
                    } finally {
                        semaphore.release();
                    }
                }));
            }
            List<EvalResult> attempts = new ArrayList<>(samples);
            for (int i = 0; i < futures.size(); i++) {
                attempts.add(futureResult(loaded, i + 1, futures.get(i), futures));
            }
            return EvalCaseReport.of(attempts);
        } finally {
            executor.shutdownNow();
        }
    }

    private EvalResult futureResult(
            EvalCaseLoader.LoadedCase loaded,
            int attemptNumber,
            Future<EvalResult> future,
            List<Future<EvalResult>> allFutures) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            allFutures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            return infraErrorResult(
                    loaded,
                    attemptNumber,
                    new RuntimeException("parallel eval attempt interrupted", e));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return infraErrorResult(
                    loaded,
                    attemptNumber,
                    cause instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new RuntimeException(cause));
        }
    }

    /**
     * Runs a single attempt. A failure that escapes the execution/judge pipeline (for example
     * the sandbox could not be created) is captured as an {@code INFRA_ERROR} attempt rather
     * than aborting the whole suite, so one broken case never sinks an entire run.
     */
    public EvalResult run(EvalCaseLoader.LoadedCase loaded) {
        return run(loaded, 1);
    }

    public EvalCaseReport skippedCase(
            EvalCaseLoader.LoadedCase loaded,
            EvalCaseReport.SkipReason reason,
            String detail) {
        Path projectDir = runtime == null
                ? Path.of("").toAbsolutePath().normalize()
                : runtime.projectDir();
        EvalRunManifest manifest = EvalRunManifestFactory.capture(
                projectDir,
                loaded,
                runtime,
                IsolationProfile.localUnsafe(),
                scorers.reproducibilityFingerprint(),
                Instant.now(),
                agent);
        return EvalCaseReport.skipped(loaded, manifest, reason, detail);
    }

    private EvalResult run(EvalCaseLoader.LoadedCase loaded, int attemptNumber) {
        try {
            return runOnce(loaded, attemptNumber);
        } catch (RuntimeException e) {
            return infraErrorResult(loaded, attemptNumber, e);
        }
    }

    private EvalResult runOnce(EvalCaseLoader.LoadedCase loaded, int attemptNumber) {
        EvalCase evalCase = loaded.evalCase();
        RunBudget budget = RunBudget.from(evalCase);
        try (AttemptExecution execution = attemptExecutor.execute(loaded, attemptNumber)) {
            ModeLauncher.LaunchOutcome outcome = execution.outcome();
            // Harness integrity is independent of how the agent fared: a CRASHED agent
            // pipeline is a genuine FAIL (judged below), not an infrastructure error. The
            // harness is only "broken" when a launcher could not be stopped (not quiescent),
            // leaving the workspace untrustworthy.
            EvalResult.HarnessStatus harnessStatus = outcome.quiescent()
                    ? EvalResult.HarnessStatus.OK
                    : EvalResult.HarnessStatus.INTERNAL_ERROR;
            List<DimensionScore> dimensions;
            ExecutionTrace trace = null;
            AttemptEvidenceRecorder evidenceRecorder = new AttemptEvidenceRecorder();
            long judgeStart = System.nanoTime();
            if (harnessStatus != EvalResult.HarnessStatus.OK) {
                dimensions = List.of(new DimensionScore(
                        Dimension.VERIFY,
                        EvalResult.JudgeStatus.NOT_RUN,
                        true,
                        "judge skipped because eval execution did not finish in a trustworthy state"));
            } else if (outcome.transientProviderFailure()) {
                dimensions = List.of(new DimensionScore(
                        Dimension.VERIFY,
                        EvalResult.JudgeStatus.NOT_RUN,
                        true,
                        "judge skipped because provider failure is transient infrastructure: "
                                + outcome.apiFailure().detail()));
            } else {
                trace = execution.trace();
                dimensions = scorers.run(
                        evalCase,
                        new ScoringContext(execution.environment(), trace, budget, evidenceRecorder));
            }
            long judgeDurationMs = (System.nanoTime() - judgeStart) / 1_000_000;
            EvalResult.JudgeStatus judgeStatus = aggregateJudgeStatus(dimensions);
            EvalResult.FinalVerdict verdict =
                    verdict(harnessStatus, outcome.status(), judgeStatus, outcome.transientProviderFailure());
            String detail = outcome.detail();
            if (outcome.transientProviderFailure() && !dimensions.isEmpty()) {
                detail = detail + "\n" + dimensions.getFirst().detail();
            }
            RunMetrics authoritativeMetrics = trace == null || trace.metrics() == null
                    ? outcome.metrics()
                    : trace.metrics();

            EvalResult result = new EvalResult(
                    evalCase.id(),
                    evalCase.mode(),
                    evalCase.capabilities(),
                    verdict,
                    harnessStatus,
                    outcome.status(),
                    judgeStatus,
                    dimensions,
                    execution.executionDurationMs(),
                    judgeDurationMs,
                    authoritativeMetrics,
                    outcome.terminalSummary(),
                    detail,
                    execution.manifest());
            return attachArtifacts(evalCase, attemptNumber, evidenceRecorder.evidence(trace), result);
        }
    }

    /** Builds an {@code INFRA_ERROR} attempt for a failure that escaped the normal pipeline. */
    private EvalResult infraErrorResult(
            EvalCaseLoader.LoadedCase loaded,
            int attemptNumber,
            RuntimeException e) {
        EvalCase evalCase = loaded.evalCase();
        Path projectDir = runtime == null
                ? Path.of("").toAbsolutePath().normalize()
                : runtime.projectDir();
        EvalRunManifest manifest = EvalRunManifestFactory.capture(
                projectDir, loaded, runtime,
                IsolationProfile.localUnsafe(),
                scorers.reproducibilityFingerprint(),
                Instant.now(),
                agent);
        EvalResult result = new EvalResult(
                evalCase.id(),
                evalCase.mode(),
                evalCase.capabilities(),
                EvalResult.FinalVerdict.INFRA_ERROR,
                EvalResult.HarnessStatus.INTERNAL_ERROR,
                EvalResult.ExecutionStatus.CRASHED,
                EvalResult.JudgeStatus.NOT_RUN,
                List.of(new DimensionScore(
                        Dimension.VERIFY,
                        EvalResult.JudgeStatus.NOT_RUN,
                        true,
                        "harness failed before scoring")),
                0,
                0,
                RunMetrics.ZERO,
                "INFRA_ERROR",
                "harness failed before/around execution: " + errorMessage(e),
                manifest);
        return attachArtifacts(evalCase, attemptNumber, new AttemptEvidence(null, null), result);
    }

    private EvalResult attachArtifacts(
            EvalCase evalCase,
            int attemptNumber,
            AttemptEvidence evidence,
            EvalResult result) {
        try {
            return result.withArtifacts(artifactWriter.write(evalCase, attemptNumber, evidence, result));
        } catch (RuntimeException e) {
            AttemptArtifacts artifacts = new AttemptArtifacts(
                    null,
                    List.of(),
                    List.of("failed to write attempt artifacts: " + errorMessage(e)));
            return result.withArtifacts(artifacts);
        }
    }

    private static EvalResult.FinalVerdict verdict(
            EvalResult.HarnessStatus harness,
            EvalResult.ExecutionStatus execution,
            EvalResult.JudgeStatus judge,
            boolean transientProviderFailure) {
        if (transientProviderFailure || harness != EvalResult.HarnessStatus.OK || judge == EvalResult.JudgeStatus.ERROR) {
            return EvalResult.FinalVerdict.INFRA_ERROR;
        }
        return execution == EvalResult.ExecutionStatus.COMPLETED
                && judge == EvalResult.JudgeStatus.PASS
                ? EvalResult.FinalVerdict.PASS
                : EvalResult.FinalVerdict.FAIL;
    }

    private static EvalResult.JudgeStatus aggregateJudgeStatus(
            List<DimensionScore> dimensions) {
        if (dimensions.stream()
                .filter(DimensionScore::gating)
                .anyMatch(score -> score.status() == EvalResult.JudgeStatus.ERROR)) {
            return EvalResult.JudgeStatus.ERROR;
        }
        if (dimensions.stream()
                .filter(DimensionScore::gating)
                .anyMatch(score -> score.status() != EvalResult.JudgeStatus.PASS)) {
            return EvalResult.JudgeStatus.FAIL;
        }
        return EvalResult.JudgeStatus.PASS;
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
