package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;
import madacode.core.session.ConversationSession;
import madacode.services.api.ApiFailureClassification;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

/** Host JVM attempt executor used by the current LOCAL_UNSAFE backend. */
final class LocalAttemptExecutor implements AttemptExecutor {

    private static final Duration HARNESS_GRACE = Duration.ofSeconds(30);

    private final HeadlessAgentRuntime runtime;
    private final ModeLauncherRegistry launchers;
    private final EvalExecutionEnvironmentFactory environments;
    private final String scorerFingerprint;
    private final EvalAgent agent;

    LocalAttemptExecutor(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            EvalExecutionEnvironmentFactory environments,
            String scorerFingerprint) {
        this(runtime, launchers, environments, scorerFingerprint, EvalAgent.MADACODE);
    }

    LocalAttemptExecutor(
            HeadlessAgentRuntime runtime,
            ModeLauncherRegistry launchers,
            EvalExecutionEnvironmentFactory environments,
            String scorerFingerprint,
            EvalAgent agent) {
        this.runtime = runtime;
        this.launchers = Objects.requireNonNull(launchers, "launchers");
        this.environments = Objects.requireNonNull(environments, "environments");
        this.scorerFingerprint = scorerFingerprint == null ? "(none)" : scorerFingerprint;
        this.agent = agent == null ? EvalAgent.MADACODE : agent;
    }

    @Override
    public AttemptExecution execute(EvalCaseLoader.LoadedCase loaded, int attemptNumber) {
        EvalCase evalCase = loaded.evalCase();
        RunBudget budget = RunBudget.from(evalCase);
        Instant startedAt = Instant.now();
        Path projectDir = runtime == null
                ? Path.of("").toAbsolutePath().normalize()
                : runtime.projectDir();
        EvalExecutionEnvironment environment = environments.create(loaded);
        try {
            EvalRunManifest manifest = EvalRunManifestFactory.capture(
                    projectDir,
                    loaded,
                    runtime,
                    environment.isolationProfile(),
                    scorerFingerprint,
                    startedAt,
                    agent);
            ConversationSession session = new ConversationSession(environment.workspace());
            session.setPermissionMode(evalCase.permissionMode());
            session.setIsolationProfile(environment.isolationProfile());
            ModeLauncher launcher = launchers.resolve(evalCase.mode());
            ExecutionTraceCollector traceCollector =
                    new ExecutionTraceCollector(environment.workspace());
            session.setSubAgentSpawnObserver(traceCollector::trackSubAgent);

            long start = System.nanoTime();
            ModeLauncher.LaunchOutcome outcome = executeWithBudget(
                    launcher,
                    evalCase,
                    session,
                    new EvalRunContext(runtime, budget, traceCollector));
            long executionDurationMs = (System.nanoTime() - start) / 1_000_000;
            ExecutionTrace trace = null;
            if (outcome.quiescent() && !outcome.transientProviderFailure()) {
                traceCollector.recordSession(session, ToolInvocation.Phase.CONTROL);
                trace = traceCollector.finish(outcome.finalText(), outcome.metrics());
            }
            return new AttemptExecution(
                    environment,
                    manifest,
                    outcome,
                    trace,
                    executionDurationMs);
        } catch (RuntimeException | Error e) {
            environment.close();
            throw e;
        }
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
                java.util.Optional<ApiFailureClassification> apiFailure =
                        ApiFailureClassification.findIn(cause);
                return new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.CRASHED,
                        RunMetrics.fromSession(session, 0),
                        apiFailure.map(failure -> "CRASHED " + failure.detail()).orElse("CRASHED"),
                        apiFailure.map(failure -> errorMessage(cause) + "\n" + failure.detail())
                                .orElseGet(() -> errorMessage(cause)),
                        "",
                        true,
                        apiFailure.orElse(null));
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
