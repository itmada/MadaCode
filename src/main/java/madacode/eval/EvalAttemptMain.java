package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.bootstrap.HeadlessAgentRuntime;
import madacode.core.session.ConversationSession;
import madacode.services.api.ApiFailureClassification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
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
 * Container entrypoint for one eval attempt.
 *
 * <p>The input DTO intentionally contains case metadata only, not the original case directory
 * or verify script path. Judge assets are mounted later by the host-side scorer.
 */
public final class EvalAttemptMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HARNESS_GRACE = Duration.ofSeconds(30);

    private EvalAttemptMain() {
    }

    public static void main(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
            run(parsed);
        } catch (Exception e) {
            System.err.println("eval-attempt: " + errorMessage(e));
            System.exit(2);
        }
    }

    static AttemptExecutionResultJson runForTest(EvalAttemptInputJson input, Path workspace) {
        return runAttempt(input, workspace);
    }

    private static void run(Args args) throws IOException {
        EvalAttemptInputJson input = MAPPER.readValue(args.input().toFile(), EvalAttemptInputJson.class);
        AttemptExecutionResultJson result = runAttempt(input, args.workspace());
        Path parent = args.output().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(args.output().toFile(), result);
    }

    private static AttemptExecutionResultJson runAttempt(EvalAttemptInputJson input, Path workspace) {
        EvalCase evalCase = input.evalCaseDomain();
        RunBudget budget = RunBudget.from(evalCase);
        ExecutionTraceCollector traceCollector = new ExecutionTraceCollector(workspace);
        Path tempHome = null;
        String previousHome = System.getProperty("user.home");
        String previousModelLogDir = System.getProperty("MADA_MODEL_RESPONSE_LOG_DIR");
        try {
            tempHome = materializeHome(input);
            if (tempHome != null) {
                System.setProperty("user.home", tempHome.toString());
                System.setProperty("MADA_MODEL_RESPONSE_LOG_DIR", tempHome.resolve(".mada/model-responses").toString());
            }
            try (HeadlessAgentRuntime runtime = runtime(input)) {
                ConversationSession session = new ConversationSession(workspace);
                session.setPermissionMode(evalCase.permissionMode());
                session.setIsolationProfile(madacode.governance.IsolationProfile.containerOpenNetwork());
                session.setSubAgentSpawnObserver(traceCollector::trackSubAgent);
                ModeLauncher launcher = launcher(input, evalCase);
                ModeLauncher.LaunchOutcome outcome = executeWithBudget(
                        launcher,
                        evalCase,
                        session,
                        new EvalRunContext(runtime, budget, traceCollector));
                ExecutionTrace trace = null;
                if (outcome.quiescent() && !outcome.transientProviderFailure()) {
                    traceCollector.recordSession(session, ToolInvocation.Phase.CONTROL);
                    trace = traceCollector.finish(outcome.finalText(), outcome.metrics());
                }
                return result(evalCase, outcome, trace, input.diagnostics());
            }
        } catch (RuntimeException e) {
            return result(
                    evalCase,
                    entrypointFailureOutcome(e),
                    null,
                    append(input.diagnostics(), "entrypoint-crash"));
        } finally {
            if (previousHome != null) {
                System.setProperty("user.home", previousHome);
            }
            if (previousModelLogDir == null) {
                System.clearProperty("MADA_MODEL_RESPONSE_LOG_DIR");
            } else {
                System.setProperty("MADA_MODEL_RESPONSE_LOG_DIR", previousModelLogDir);
            }
            deleteTree(tempHome);
        }
    }

    private static Path materializeHome(EvalAttemptInputJson input) {
        if (!EvalAttemptInputJson.MODE_RUNTIME.equals(input.executionMode())) {
            return null;
        }
        try {
            Path home = Files.createTempDirectory("mada-eval-container-home-");
            Path providersFile = home.resolve(".mada/providers.json");
            Files.createDirectories(providersFile.getParent());
            Files.writeString(providersFile, input.providerConfigJson());
            return home;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to materialize provider config", e);
        }
    }

    private static HeadlessAgentRuntime runtime(EvalAttemptInputJson input) {
        if (EvalAttemptInputJson.MODE_NO_MODEL.equals(input.executionMode())) {
            return null;
        }
        return HeadlessAgentRuntime.create(Path.of(input.projectDir()));
    }

    private static ModeLauncher launcher(EvalAttemptInputJson input, EvalCase evalCase) {
        if (EvalAttemptInputJson.MODE_NO_MODEL.equals(input.executionMode())) {
            return new NoOpModeLauncher(evalCase.mode());
        }
        return ModeLauncherRegistry.defaults().resolve(evalCase.mode());
    }

    private static ModeLauncher.LaunchOutcome executeWithBudget(
            ModeLauncher launcher,
            EvalCase evalCase,
            ConversationSession session,
            EvalRunContext context) {
        Duration innerDeadline = context.budget().caseTimeout();
        Duration outerDeadline = innerDeadline.plus(HARNESS_GRACE);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mada-eval-container-case-", 0).factory());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("mada-eval-container-deadline-", 0).factory());
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
                        "eval attempt interrupted",
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
                outcome.quiescent(),
                outcome.apiFailure());
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

    private static AttemptExecutionResultJson result(
            EvalCase evalCase,
            ModeLauncher.LaunchOutcome outcome,
            ExecutionTrace trace,
            List<String> diagnostics) {
        return new AttemptExecutionResultJson(
                AttemptExecutionResultJson.SCHEMA_VERSION,
                evalCase.id(),
                evalCase.mode(),
                outcome.status().name(),
                outcome.terminalSummary(),
                outcome.detail(),
                outcome.finalText(),
                EvalReportJson.metricsJson(outcome.metrics()),
                AttemptExecutionResultJson.ApiFailureJson.from(outcome.apiFailure()),
                outcome.quiescent(),
                traceJson(trace, outcome.finalText(), outcome.metrics()),
                diagnostics);
    }

    private static AttemptExecutionResultJson.TraceJson traceJson(
            ExecutionTrace trace,
            String finalText,
            RunMetrics metrics) {
        if (trace == null) {
            return new AttemptExecutionResultJson.TraceJson(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    finalText,
                    EvalReportJson.metricsJson(metrics));
        }
        return new AttemptExecutionResultJson.TraceJson(
                trace.invocations().stream()
                        .map(invocation -> new AttemptExecutionResultJson.InvocationJson(
                                invocation.name(),
                                invocation.inputJson(),
                                invocation.resultJson(),
                                invocation.accessEvidence(),
                                invocation.phase().name(),
                                invocation.ordinal()))
                        .toList(),
                trace.fileEffects(),
                trace.userTurns(),
                trace.assistantTurns(),
                trace.finalText(),
                EvalReportJson.metricsJson(trace.metrics()));
    }

    private static ModeLauncher.LaunchOutcome entrypointFailureOutcome(Throwable throwable) {
        return new ModeLauncher.LaunchOutcome(
                EvalResult.ExecutionStatus.CRASHED,
                RunMetrics.ZERO,
                "INFRA_ERROR",
                errorMessage(throwable),
                "",
                false,
                ApiFailureClassification.findIn(throwable).orElse(null));
    }

    private static List<String> append(List<String> diagnostics, String value) {
        java.util.ArrayList<String> copy = new java.util.ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private record Args(Path input, Path workspace, Path output) {
        static Args parse(String[] args) {
            Path input = null;
            Path workspace = null;
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input" -> input = Path.of(value(args, ++i, "--input"));
                    case "--workspace" -> workspace = Path.of(value(args, ++i, "--workspace"));
                    case "--output" -> output = Path.of(value(args, ++i, "--output"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("--input is required");
            }
            if (workspace == null) {
                throw new IllegalArgumentException("--workspace is required");
            }
            if (output == null) {
                throw new IllegalArgumentException("--output is required");
            }
            return new Args(input, workspace, output);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
