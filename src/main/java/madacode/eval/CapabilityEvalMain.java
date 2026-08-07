package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Standalone entry point for the capability eval — deliberately separate from the {@code mada}
 * CLI so real-model runs never enter the production command surface or {@code ./mvnw test}.
 *
 * <p>Usage:
 * <pre>
 *   bin/eval --unsafe-local       run every case against the real model
 *   bin/eval --self-test          validate sandbox+scoring with no model calls
 *   bin/eval --unsafe-local --case &lt;id&gt;
 *   bin/eval --unsafe-local --mode &lt;mode&gt;
 *   bin/eval --unsafe-local --capability &lt;tag&gt;
 *   bin/eval --cases-dir &lt;path&gt;   override the cases directory (default: ./eval/cases)
 *   bin/eval --max-total-tokens &lt;n&gt; skip remaining cases after the run reaches n tokens
 *   bin/eval --concurrency &lt;n&gt;    run cases with bounded parallelism
 *   bin/eval --resume &lt;run-dir&gt;   reuse complete cases from a previous run directory
 *   bin/eval --backend docker      run no-model self-test attempts through Docker
 *   bin/eval --compare &lt;baseline.json&gt; &lt;candidate.json&gt;
 *   bin/eval --compare ... --out &lt;file&gt;  optionally write the compare markdown report
 * </pre>
 */
public final class CapabilityEvalMain {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("eval: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void run(String[] args) {
        Options opts = Options.parse(args);
        Path projectDir = Path.of("").toAbsolutePath();
        if (opts.compareBaseline != null) {
            runCompare(opts);
            return;
        }
        Path casesDir = opts.casesDir != null ? opts.casesDir : projectDir.resolve("eval/cases");

        List<EvalCaseLoader.LoadedCase> all = new EvalCaseLoader(casesDir).loadAll();
        List<EvalCaseLoader.LoadedCase> selected = all.stream()
                .filter(c -> opts.caseId == null || opts.caseId.equals(c.evalCase().id()))
                .filter(c -> opts.mode == null || opts.mode.equals(c.evalCase().mode()))
                .filter(c -> opts.capability == null || c.evalCase().capabilities().contains(opts.capability))
                .filter(c -> opts.selfTest == c.evalCase().capabilities().contains("selftest"))
                .toList();

        if (selected.isEmpty()) {
            System.err.println("No matching cases under " + casesDir
                    + (opts.selfTest ? " (self-test looks for cases tagged 'selftest')" : ""));
            System.exit(2);
        }
        validateConcurrency(opts, selected);
        validateBackend(opts, selected);

        if (opts.out != null) {
            throw new IllegalArgumentException(
                    "--out is only valid with --compare; eval HTML/JSON reports are written under "
                            + "eval/reports/run-<timestamp>/");
        }
        Path runDir = resolveRunDir(projectDir, opts.resumeDir);
        AttemptArtifactWriter artifactWriter = new FileAttemptArtifactWriter(runDir);
        EvalRunLimit runLimit = opts.maxTotalTokens == null
                ? EvalRunLimit.NONE
                : EvalRunLimit.maxTotalTokens(opts.maxTotalTokens);
        EvalCostEstimator costEstimator = EvalCostEstimator.fromDefaultProviderConfig(projectDir);
        ScorerPipeline scorers = defaultScorerPipeline();
        EvalResumeStore resumeStore = opts.resumeDir == null ? null : EvalResumeStore.open(opts.resumeDir);
        EvalReportCheckpointStore reportStore = new EvalReportCheckpointStore(
                runDir,
                costEstimator,
                selected.size());

        if (opts.selfTest) {
            List<EvalCaseReport> results = runSelfTest(
                    selected,
                    artifactWriter,
                    runLimit,
                    opts.concurrency,
                    scorers,
                    resumeStore,
                    opts.backend,
                    reportStore);
            printRunSummary(results);
            // A self-test passes when each case matches its explicit expectation.
            boolean ok = true;
            for (EvalCaseReport r : results) {
                EvalCase evalCase = selected.stream()
                        .filter(c -> c.evalCase().id().equals(r.id()))
                        .findFirst()
                        .orElseThrow()
                        .evalCase();
                boolean expectPass = "PASS".equals(evalCase.expectedVerdict());
                boolean actualPass = r.gateVerdict() == EvalCaseReport.GateVerdict.PASS;
                if (actualPass != expectPass) {
                    ok = false;
                    System.out.println("  mismatch: " + r.id() + " expected "
                            + (expectPass ? "PASS" : "FAIL") + " but was "
                            + r.gateVerdict());
                }
            }
            System.out.println(ok ? "SELF-TEST OK (sandbox + scorer plumbing verified)" : "SELF-TEST FAILED");
            System.exit(ok ? 0 : 1);
        }

        if (opts.backend == EvalBackend.LOCAL && !opts.unsafeLocal) {
            System.err.println("Real-model eval currently uses LOCAL_UNSAFE isolation. "
                    + "Re-run with --unsafe-local to acknowledge that agent processes can reach the host, "
                    + "network, and repository files, including judge scripts. Treat this as a local smoke "
                    + "measurement, not a hidden-judge benchmark. Use only trusted cases.");
            System.exit(2);
        }

        List<EvalCaseReport> results = runWithModel(
                projectDir,
                selected,
                artifactWriter,
                runLimit,
                opts.concurrency,
                scorers,
                resumeStore,
                opts.backend,
                reportStore,
                opts.agent);
        printRunSummary(results);

        long gateFailures = results.stream()
                .filter(r -> r.gateVerdict() != EvalCaseReport.GateVerdict.PASS)
                .count();
        if (gateFailures > 0) {
            System.err.println("eval: " + gateFailures
                    + " case(s) did not pass the gate. pass@k is reported for exploration, "
                    + "but the process exits successfully only when every sample passes "
                    + "without infrastructure errors.");
        }
        System.exit(gateFailures == 0 ? 0 : 1);
    }

    private static void runCompare(Options opts) {
        if (opts.selfTest || opts.unsafeLocal || opts.caseId != null
                || opts.mode != null || opts.capability != null || opts.casesDir != null
                || opts.maxTotalTokens != null
                || opts.concurrency != 1 || opts.resumeDir != null
                || opts.backend != EvalBackend.LOCAL) {
            throw new IllegalArgumentException(
                    "--compare may only be combined with --out");
        }
        EvalReportCompare.Comparison comparison =
                EvalReportCompare.compare(opts.compareBaseline, opts.compareCandidate);
        String report = EvalReportCompare.renderMarkdown(comparison);
        System.out.println(report);
        if (opts.out != null) {
            writeText(opts.out, report, "Compare report written to ");
        }
        System.exit(comparison.hasGateRegression() ? 1 : 0);
    }

    /** Zero-cost plumbing check: no model, no runtime — the no-op launcher leaves workspaces as-is. */
    private static List<EvalCaseReport> runSelfTest(
            List<EvalCaseLoader.LoadedCase> cases,
            AttemptArtifactWriter artifactWriter,
            EvalRunLimit runLimit,
            int concurrency,
            ScorerPipeline scorers,
            EvalResumeStore resumeStore,
            EvalBackend backend,
            EvalReportCheckpointStore reportStore) {
        if (backend == EvalBackend.DOCKER) {
            EvalRunner runner = new EvalRunner(
                    null,
                    scorers,
                    new DockerAttemptExecutor(null, scorers.reproducibilityFingerprint()),
                    artifactWriter);
            return runCases(runner, cases, runLimit, concurrency, resumeStore, scorers, reportStore,
                    EvalAgent.MADACODE);
        }
        ModeLauncherRegistry registry = new ModeLauncherRegistry();
        cases.stream()
                .map(c -> c.evalCase().mode())
                .distinct()
                .forEach(mode -> registry.register(new NoOpModeLauncher(mode)));
        EvalRunner runner = new EvalRunner(
                null,
                registry,
                scorers,
                Sandbox::of,
                artifactWriter);
        return runCases(runner, cases, runLimit, concurrency, resumeStore, scorers, reportStore,
                EvalAgent.MADACODE);
    }

    private static List<EvalCaseReport> runWithModel(
            Path projectDir,
            List<EvalCaseLoader.LoadedCase> cases,
            AttemptArtifactWriter artifactWriter,
            EvalRunLimit runLimit,
            int concurrency,
            ScorerPipeline scorers,
            EvalResumeStore resumeStore,
            EvalBackend backend,
            EvalReportCheckpointStore reportStore,
            EvalAgent agent) {
        ModeLauncherRegistry registry = agent == EvalAgent.CLAUDE
                ? claudeRegistry(cases)
                : ModeLauncherRegistry.defaults();
        cases.forEach(evalCase -> registry.resolve(evalCase.evalCase().mode()));
        try (HeadlessAgentRuntime runtime = HeadlessAgentRuntime.create(projectDir)) {
            EvalExecutionEnvironmentFactory localEnvironments =
                    GitWorktreeEvalExecutionEnvironment.factory(projectDir);
            EvalRunner runner = backend == EvalBackend.DOCKER
                    ? new EvalRunner(
                            runtime,
                            scorers,
                            new DockerAttemptExecutor(runtime, scorers.reproducibilityFingerprint()),
                            artifactWriter)
                    : new EvalRunner(
                            runtime,
                            registry,
                            scorers,
                            localEnvironments,
                            artifactWriter,
                            agent);
            return runCases(runner, cases, runLimit, concurrency, resumeStore, scorers, reportStore, agent);
        }
    }

    /** Registers the Claude Code CLI launcher under every distinct mode present in the cases. */
    private static ModeLauncherRegistry claudeRegistry(List<EvalCaseLoader.LoadedCase> cases) {
        ModeLauncherRegistry registry = new ModeLauncherRegistry();
        cases.stream()
                .map(c -> c.evalCase().mode())
                .distinct()
                .forEach(mode -> registry.register(
                        new ClaudeCodeModeLauncher("claude", new ProcessSupervisor(), mode)));
        return registry;
    }

    private static List<EvalCaseReport> runCases(
            EvalRunner runner,
            List<EvalCaseLoader.LoadedCase> cases,
            EvalRunLimit runLimit,
            int concurrency,
            EvalResumeStore resumeStore,
            ScorerPipeline scorers,
            EvalReportCheckpointStore reportStore,
            EvalAgent agent) {
        if (concurrency > 1 && runLimit.maxTotalTokens() == null) {
            return runCasesParallel(
                    runner,
                    cases,
                    concurrency,
                    resumeStore,
                    scorers,
                    reportStore,
                    agent);
        }
        List<EvalCaseReport> reports = new java.util.ArrayList<>();
        RunMetrics accumulated = RunMetrics.ZERO;
        String scorerFingerprint = scorers.reproducibilityFingerprint();
        try {
            for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
                EvalCaseLoader.LoadedCase loaded = cases.get(caseIndex);
                java.util.Optional<EvalCaseReport> resumed = resumeStore == null
                        ? java.util.Optional.empty()
                        : resumeStore.reusableCase(loaded, scorerFingerprint);
                EvalCaseReport report;
                if (resumed.isPresent()) {
                    report = resumed.get();
                } else if (agent == EvalAgent.CLAUDE && isClaudeIncompatible(loaded)) {
                    report = runner.skippedCase(
                            loaded,
                            EvalCaseReport.SkipReason.AGENT_INCOMPATIBLE,
                            "gating process dimension is MadaCode-specific; skipped under --agent claude");
                } else if (runLimit.shouldSkipNextCase(accumulated)) {
                    report = runner.skippedCase(
                            loaded,
                            EvalCaseReport.SkipReason.BUDGET,
                            runLimit.skipDetail(accumulated));
                } else {
                    report = runner.runCase(loaded, concurrency);
                }
                reports.add(report);
                accumulated = accumulated.plus(report.totalMetrics());
                String nextCaseId = caseIndex + 1 < cases.size()
                        ? cases.get(caseIndex + 1).evalCase().id()
                        : null;
                reportStore.caseCompleted(report, List.copyOf(reports), nextCaseId);
            }
            reportStore.completed(List.copyOf(reports));
            return reports;
        } catch (RuntimeException e) {
            try {
                reportStore.aborted(List.copyOf(reports), e);
            } catch (RuntimeException checkpointFailure) {
                e.addSuppressed(checkpointFailure);
            }
            throw e;
        }
    }

    private static List<EvalCaseReport> runCasesParallel(
            EvalRunner runner,
            List<EvalCaseLoader.LoadedCase> cases,
            int concurrency,
            EvalResumeStore resumeStore,
            ScorerPipeline scorers,
            EvalReportCheckpointStore reportStore,
            EvalAgent agent) {
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(
                        concurrency,
                        Thread.ofVirtual().name("mada-eval-case-", 0).factory());
        String scorerFingerprint = scorers.reproducibilityFingerprint();
        List<EvalCaseReport> prepared = new java.util.ArrayList<>();
        List<java.util.concurrent.Future<EvalCaseReport>> futures = new java.util.ArrayList<>();
        List<EvalCaseReport> reports = new java.util.ArrayList<>();
        try {
            for (EvalCaseLoader.LoadedCase loaded : cases) {
                java.util.Optional<EvalCaseReport> resumed = resumeStore == null
                        ? java.util.Optional.empty()
                        : resumeStore.reusableCase(loaded, scorerFingerprint);
                if (resumed.isPresent()) {
                    prepared.add(resumed.get());
                    futures.add(null);
                    continue;
                }
                if (agent == EvalAgent.CLAUDE && isClaudeIncompatible(loaded)) {
                    prepared.add(runner.skippedCase(
                            loaded,
                            EvalCaseReport.SkipReason.AGENT_INCOMPATIBLE,
                            "gating process dimension is MadaCode-specific; skipped under --agent claude"));
                    futures.add(null);
                    continue;
                }
                prepared.add(null);
                futures.add(executor.submit(() -> {
                    // The outer executor owns the global request budget. Keep attempts within
                    // one case sequential so concurrency is the total number of active cases.
                    return runner.runCase(loaded, 1);
                }));
            }

            for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
                EvalCaseReport report;
                if (prepared.get(caseIndex) != null) {
                    report = prepared.get(caseIndex);
                } else {
                    try {
                        report = futures.get(caseIndex).get();
                    } catch (InterruptedException e) {
                        futures.stream().filter(java.util.Objects::nonNull).forEach(f -> f.cancel(true));
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("parallel eval case interrupted", e);
                    } catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        throw cause instanceof RuntimeException runtimeException
                                ? runtimeException
                                : new IllegalStateException("parallel eval case failed", cause);
                    }
                }
                reports.add(report);
                String nextCaseId = caseIndex + 1 < cases.size()
                        ? cases.get(caseIndex + 1).evalCase().id()
                        : null;
                reportStore.caseCompleted(report, List.copyOf(reports), nextCaseId);
            }
            reportStore.completed(List.copyOf(reports));
            return reports;
        } catch (RuntimeException e) {
            futures.stream().filter(java.util.Objects::nonNull).forEach(f -> f.cancel(true));
            try {
                reportStore.aborted(List.copyOf(reports), e);
            } catch (RuntimeException checkpointFailure) {
                e.addSuppressed(checkpointFailure);
            }
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void validateConcurrency(
            Options opts,
            List<EvalCaseLoader.LoadedCase> selected) {
        if (opts.concurrency <= 0) {
            throw new IllegalArgumentException("--concurrency requires a positive integer");
        }
        if (opts.concurrency > 1
                && selected.stream().anyMatch(c -> "long-running".equals(c.evalCase().mode()))) {
            throw new IllegalArgumentException("--concurrency > 1 is not enabled for long-running cases "
                    + "until shared runtime and worker storage thread-safety are audited");
        }
    }

    private static void validateBackend(
            Options opts,
            List<EvalCaseLoader.LoadedCase> selected) {
        if (opts.backend == EvalBackend.DOCKER
                && selected.stream().anyMatch(c -> c.evalCase().hasGitBaseline())) {
            throw new IllegalArgumentException(
                    "--backend docker does not yet provide clean Git worktree judging for SWE cases; "
                            + "use the local backend with --unsafe-local");
        }
    }

    /**
     * True when a case gates on a process dimension (trajectory / safety / dialog) that the
     * external Claude Code CLI cannot evidence, because it does not flow through MadaCode's
     * tool pipeline. Such cases are skipped under {@code --agent claude}.
     */
    private static boolean isClaudeIncompatible(EvalCaseLoader.LoadedCase loaded) {
        EvalChecks checks = loaded.evalCase().checks();
        if (checks == null || checks.isEmpty()) {
            return false;
        }
        if (checks.trajectory() != null && checks.trajectory().gatingOrDefault()) {
            return true;
        }
        if (checks.safety() != null && checks.safety().gatingOrDefault()) {
            return true;
        }
        return checks.dialog() != null && checks.dialog().gatingOrDefault();
    }

    /**
     * Built-in dimensions share one ordered pipeline. Dialog rubric checks intentionally
     * receive no client until a runtime adapter can truthfully enforce and record judge
     * sampling settings; deterministic dialog checks remain available in the meantime.
     */
    private static ScorerPipeline defaultScorerPipeline() {
        return ScorerPipeline.of(
                new VerifyScriptScorer(),
                new TrajectoryScorer(),
                new EfficiencyScorer(),
                new DialogJudgeScorer(),
                new SafetyScorer());
    }

    private static Path resolveRunDir(Path projectDir, Path resumeDir) {
        if (resumeDir != null) {
            return resumeDir.toAbsolutePath().normalize();
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(ZonedDateTime.now(ZoneId.systemDefault()));
        return projectDir.resolve("eval/reports/run-" + stamp);
    }

    private static void printRunSummary(List<EvalCaseReport> reports) {
        long stable = reports.stream().filter(EvalCaseReport::stable).count();
        long passAtK = reports.stream().filter(EvalCaseReport::passed).count();
        long skipped = reports.stream().filter(EvalCaseReport::skipped).count();
        System.out.println("Eval 完成：稳定通过 " + stable + "/" + reports.size()
                + "，pass@k 通过 " + passAtK + "/" + reports.size()
                + (skipped == 0 ? "" : "，跳过 " + skipped));
    }

    private record Options(
            boolean selfTest,
            boolean unsafeLocal,
            String caseId,
            String mode,
            String capability,
            Path casesDir,
            Path out,
            Path jsonOut,
            Long maxTotalTokens,
            int concurrency,
            Path resumeDir,
            EvalBackend backend,
            EvalAgent agent,
            Path compareBaseline,
            Path compareCandidate) {

        static Options parse(String[] args) {
            boolean selfTest = false;
            boolean unsafeLocal = false;
            String caseId = null;
            String mode = null;
            String capability = null;
            Path casesDir = null;
            Path out = null;
            Path jsonOut = null;
            Long maxTotalTokens = null;
            int concurrency = 1;
            Path resumeDir = null;
            EvalBackend backend = EvalBackend.LOCAL;
            EvalAgent agent = EvalAgent.MADACODE;
            Path compareBaseline = null;
            Path compareCandidate = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--self-test" -> selfTest = true;
                    case "--unsafe-local" -> unsafeLocal = true;
                    case "--case" -> caseId = value(args, ++i, "--case");
                    case "--mode" -> mode = value(args, ++i, "--mode");
                    case "--capability" -> capability = value(args, ++i, "--capability");
                    case "--cases-dir" -> casesDir = Path.of(value(args, ++i, "--cases-dir"));
                    case "--out" -> out = Path.of(value(args, ++i, "--out"));
                    case "--json-out" -> jsonOut = Path.of(value(args, ++i, "--json-out"));
                    case "--max-total-tokens" -> maxTotalTokens =
                            positiveLong(value(args, ++i, "--max-total-tokens"), "--max-total-tokens");
                    case "--concurrency" -> concurrency =
                            positiveInt(value(args, ++i, "--concurrency"), "--concurrency");
                    case "--resume" -> resumeDir = Path.of(value(args, ++i, "--resume"));
                    case "--backend" -> backend = EvalBackend.parse(value(args, ++i, "--backend"));
                    case "--agent" -> agent = EvalAgent.parse(value(args, ++i, "--agent"));
                    case "--compare" -> {
                        compareBaseline = Path.of(value(args, ++i, "--compare"));
                        compareCandidate = Path.of(value(args, ++i, "--compare"));
                    }
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(
                    selfTest, unsafeLocal, caseId, mode, capability,
                    casesDir, out, jsonOut, maxTotalTokens, concurrency, resumeDir,
                    backend, agent, compareBaseline, compareCandidate);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static long positiveLong(String value, String option) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) {
                    throw new NumberFormatException("not positive");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " requires a positive integer");
            }
        }

        private static int positiveInt(String value, String option) {
            long parsed = positiveLong(value, option);
            if (parsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(option + " is too large");
            }
            return (int) parsed;
        }
    }

    private static void writeText(Path out, String content, String successPrefix) {
        EvalReportCheckpointStore.atomicWrite(out, content);
        System.out.println(successPrefix + out);
    }
}
