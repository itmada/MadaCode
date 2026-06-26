package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;
import madacode.core.session.ConversationSession;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Grace window added on top of a case's wall-clock timeout for the outer harness backstop.
     * The inner deadline ({@code caseTimeout}) interrupts the launcher cooperatively; this
     * outer backstop only fires when a launcher ignores interruption and is genuinely wedged,
     * so a normal timeout is always classified as a clean {@code TIMED_OUT} rather than an
     * infrastructure error.
     */
    private static final Duration HARNESS_GRACE = Duration.ofSeconds(30);

    private final HeadlessAgentRuntime runtime;
    private final ModeLauncherRegistry launchers;
    private final ScorerPipeline scorers;
    private final EvalExecutionEnvironmentFactory environments;

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
        this.runtime = runtime;
        this.launchers = Objects.requireNonNull(launchers, "launchers");
        this.scorers = Objects.requireNonNull(scorers, "scorers");
        this.environments = Objects.requireNonNull(environments, "environments");
    }

    public List<EvalCaseReport> runAll(List<EvalCaseLoader.LoadedCase> cases) {
        List<EvalCaseReport> reports = new ArrayList<>();
        for (EvalCaseLoader.LoadedCase loaded : cases) {
            reports.add(runCase(loaded));
        }
        return reports;
    }

    /** Runs every sample for one case and aggregates them into a case-level report. */
    public EvalCaseReport runCase(EvalCaseLoader.LoadedCase loaded) {
        int samples = loaded.evalCase().samplesOrDefault();
        List<EvalResult> attempts = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            attempts.add(run(loaded));
        }
        return EvalCaseReport.of(attempts);
    }

    /**
     * Runs a single attempt. A failure that escapes the execution/judge pipeline (for example
     * the sandbox could not be created) is captured as an {@code INFRA_ERROR} attempt rather
     * than aborting the whole suite, so one broken case never sinks an entire run.
     */
    public EvalResult run(EvalCaseLoader.LoadedCase loaded) {
        try {
            return runOnce(loaded);
        } catch (RuntimeException e) {
            return infraErrorResult(loaded, e);
        }
    }

    private EvalResult runOnce(EvalCaseLoader.LoadedCase loaded) {
        EvalCase evalCase = loaded.evalCase();
        RunBudget budget = RunBudget.from(evalCase);
        Instant startedAt = Instant.now();
        Path projectDir = runtime == null
                ? Path.of("").toAbsolutePath().normalize()
                : runtime.projectDir();
        try (EvalExecutionEnvironment environment = environments.create(loaded)) {
            EvalRunManifest manifest = EvalRunManifestFactory.capture(
                    projectDir,
                    loaded,
                    runtime,
                    environment.trustProfile(),
                    scorers.reproducibilityFingerprint(),
                    startedAt);
            ConversationSession session = new ConversationSession(environment.workspace());
            session.setPermissionMode(evalCase.permissionMode());
            ModeLauncher launcher = launchers.resolve(evalCase.mode());
            ExecutionTraceCollector traceCollector =
                    new ExecutionTraceCollector(environment.workspace());
            // Sub-agents spawned from this control session (and their descendants) register
            // themselves with the collector at spawn time, so trajectory/safety/efficiency
            // checks see the whole agent tree rather than only the control transcript.
            session.setSubAgentSpawnObserver(traceCollector::trackSubAgent);

            long start = System.nanoTime();
            ModeLauncher.LaunchOutcome outcome = executeWithBudget(
                    launcher,
                    evalCase,
                    session,
                    new EvalRunContext(runtime, budget, traceCollector));
            long executionDurationMs = (System.nanoTime() - start) / 1_000_000;

            // Harness integrity is independent of how the agent fared: a CRASHED agent
            // pipeline is a genuine FAIL (judged below), not an infrastructure error. The
            // harness is only "broken" when a launcher could not be stopped (not quiescent),
            // leaving the workspace untrustworthy.
            EvalResult.HarnessStatus harnessStatus = outcome.quiescent()
                    ? EvalResult.HarnessStatus.OK
                    : EvalResult.HarnessStatus.INTERNAL_ERROR;
            List<DimensionScore> dimensions;
            long judgeStart = System.nanoTime();
            if (harnessStatus != EvalResult.HarnessStatus.OK) {
                dimensions = List.of(new DimensionScore(
                        Dimension.VERIFY,
                        EvalResult.JudgeStatus.NOT_RUN,
                        true,
                        "judge skipped because eval execution did not finish in a trustworthy state"));
            } else {
                traceCollector.recordSession(session, ToolInvocation.Phase.CONTROL);
                ExecutionTrace trace =
                        traceCollector.finish(outcome.finalText(), outcome.metrics());
                dimensions = scorers.run(
                        evalCase,
                        new ScoringContext(environment, trace, budget));
            }
            long judgeDurationMs = (System.nanoTime() - judgeStart) / 1_000_000;
            EvalResult.JudgeStatus judgeStatus = aggregateJudgeStatus(dimensions);
            EvalResult.FinalVerdict verdict =
                    verdict(harnessStatus, outcome.status(), judgeStatus);
            String detail = "execution: " + outcome.detail()
                    + "\njudge:\n" + dimensionDetails(dimensions);

            return new EvalResult(
                    evalCase.id(),
                    evalCase.mode(),
                    evalCase.capabilities(),
                    verdict,
                    harnessStatus,
                    outcome.status(),
                    judgeStatus,
                    dimensions,
                    executionDurationMs,
                    judgeDurationMs,
                    outcome.metrics(),
                    outcome.terminalSummary(),
                    detail,
                    manifest);
        }
    }

    /** Builds an {@code INFRA_ERROR} attempt for a failure that escaped the normal pipeline. */
    private EvalResult infraErrorResult(EvalCaseLoader.LoadedCase loaded, RuntimeException e) {
        EvalCase evalCase = loaded.evalCase();
        Path projectDir = runtime == null
                ? Path.of("").toAbsolutePath().normalize()
                : runtime.projectDir();
        EvalRunManifest manifest = EvalRunManifestFactory.capture(
                projectDir, loaded, runtime,
                EvalExecutionEnvironment.TrustProfile.forIsolation(
                        EvalExecutionEnvironment.IsolationLevel.LOCAL_UNSAFE),
                scorers.reproducibilityFingerprint(),
                Instant.now());
        return new EvalResult(
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
    }

    /**
     * Runs the launcher under two nested deadlines:
     * <ul>
     *   <li><b>inner</b> ({@code caseTimeout}) — a watchdog interrupts the launcher thread so
     *       cooperative modes stop and return a clean {@code TIMED_OUT};</li>
     *   <li><b>outer</b> ({@code caseTimeout + grace}) — {@code future.get} backstop that only
     *       trips when the launcher ignored the interrupt and is wedged.</li>
     * </ul>
     */
    private static ModeLauncher.LaunchOutcome executeWithBudget(
            ModeLauncher launcher,
            EvalCase evalCase,
            ConversationSession session,
            EvalRunContext context) {
        Duration innerDeadline = context.budget().caseTimeout();
        Duration outerDeadline = innerDeadline.plus(HARNESS_GRACE);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mada-eval-case-", 0).factory());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("mada-eval-deadline-", 0).factory());
        AtomicReference<Thread> running = new AtomicReference<>();
        AtomicBoolean deadlineFired = new AtomicBoolean(false);
        try {
            Future<ModeLauncher.LaunchOutcome> future = executor.submit(() -> {
                running.set(Thread.currentThread());
                return launcher.launch(evalCase, session, context);
            });
            watchdog.schedule(() -> {
                deadlineFired.set(true);
                Thread thread = running.get();
                if (thread != null) {
                    thread.interrupt();
                }
            }, innerDeadline.toMillis(), TimeUnit.MILLISECONDS);
            try {
                ModeLauncher.LaunchOutcome outcome =
                        future.get(outerDeadline.toMillis(), TimeUnit.MILLISECONDS);
                // A cooperatively-cancelled outcome after the inner deadline is a timeout,
                // not a user cancellation; normalize it. A genuine COMPLETED that won the
                // race against the deadline is left untouched.
                return deadlineFired.get() ? asTimedOut(outcome) : outcome;
            } catch (TimeoutException e) {
                future.cancel(true);
                boolean quiescent = stopExecutor(executor);
                return new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.TIMED_OUT,
                        RunMetrics.fromSession(session, 0),
                        "TIMED_OUT",
                        "case exceeded hard timeout " + outerDeadline,
                        "",
                        quiescent);
            } catch (InterruptedException e) {
                future.cancel(true);
                boolean quiescent = stopExecutor(executor);
                Thread.currentThread().interrupt();
                return new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.CANCELLED,
                        RunMetrics.fromSession(session, 0),
                        "CANCELLED",
                        "eval runner interrupted",
                        "",
                        quiescent);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.CRASHED,
                        RunMetrics.fromSession(session, 0),
                        "CRASHED",
                        errorMessage(cause));
            }
        } finally {
            watchdog.shutdownNow();
            executor.shutdownNow();
        }
    }

    private static ModeLauncher.LaunchOutcome asTimedOut(ModeLauncher.LaunchOutcome outcome) {
        if (outcome.status() != EvalResult.ExecutionStatus.CANCELLED) {
            return outcome;
        }
        return new ModeLauncher.LaunchOutcome(
                EvalResult.ExecutionStatus.TIMED_OUT,
                outcome.metrics(),
                "TIMED_OUT",
                outcome.detail(),
                outcome.finalText(),
                outcome.quiescent());
    }

    private static EvalResult.FinalVerdict verdict(
            EvalResult.HarnessStatus harness,
            EvalResult.ExecutionStatus execution,
            EvalResult.JudgeStatus judge) {
        if (harness != EvalResult.HarnessStatus.OK || judge == EvalResult.JudgeStatus.ERROR) {
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

    private static String dimensionDetails(List<DimensionScore> dimensions) {
        return dimensions.stream()
                .map(score -> score.dimension() + "=" + score.status()
                        + (score.gating() ? " [gating]" : "")
                        + "\n" + score.detail())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static boolean stopExecutor(ExecutorService executor) {
        executor.shutdown();
        if (awaitTermination(executor, 10)) {
            return true;
        }
        executor.shutdownNow();
        return awaitTermination(executor, 10);
    }

    private static boolean awaitTermination(ExecutorService executor, long seconds) {
        try {
            return executor.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
