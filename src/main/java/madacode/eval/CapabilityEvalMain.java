package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 *   bin/eval --out &lt;file&gt;         write the report to a file
 *   bin/eval --json-out &lt;file&gt;    write the JSON report to a file
 *   bin/eval --max-total-tokens &lt;n&gt; skip remaining cases after the run reaches n tokens
 *   bin/eval --concurrency &lt;n&gt;    run attempts with bounded parallelism
 *   bin/eval --resume &lt;run-dir&gt;   reuse complete cases from a previous run directory
 *   bin/eval --backend docker      run no-model self-test attempts through Docker
 *   bin/eval --compare &lt;baseline.json&gt; &lt;candidate.json&gt;
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

        ReportTargets targets = ReportTargets.resolve(projectDir, opts.out, opts.jsonOut, opts.resumeDir);
        AttemptArtifactWriter artifactWriter = new FileAttemptArtifactWriter(targets.runDir());
        EvalRunLimit runLimit = opts.maxTotalTokens == null
                ? EvalRunLimit.NONE
                : EvalRunLimit.maxTotalTokens(opts.maxTotalTokens);
        EvalCostEstimator costEstimator = EvalCostEstimator.fromDefaultProviderConfig(projectDir);
        ScorerPipeline scorers = defaultScorerPipeline();
        EvalResumeStore resumeStore = opts.resumeDir == null ? null : EvalResumeStore.open(opts.resumeDir);

        if (opts.selfTest) {
            List<EvalCaseReport> results = runSelfTest(
                    selected,
                    artifactWriter,
                    runLimit,
                    opts.concurrency,
                    scorers,
                    resumeStore,
                    opts.backend);
            String report = EvalReport.render(results, costEstimator);
            String jsonReport = EvalReportJson.render(results, costEstimator);
            System.out.println(report);
            writeReports(targets, report, jsonReport);
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
                opts.backend);
        String report = EvalReport.render(results, costEstimator);
        String jsonReport = EvalReportJson.render(results, costEstimator);
        System.out.println(report);
        writeReports(targets, report, jsonReport);

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
                || opts.jsonOut != null || opts.maxTotalTokens != null
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
            EvalBackend backend) {
        if (backend == EvalBackend.DOCKER) {
            EvalRunner runner = new EvalRunner(
                    null,
                    scorers,
                    new DockerAttemptExecutor(null, scorers.reproducibilityFingerprint()),
                    artifactWriter);
            return runCases(runner, cases, runLimit, concurrency, resumeStore, scorers);
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
        return runCases(runner, cases, runLimit, concurrency, resumeStore, scorers);
    }

    private static List<EvalCaseReport> runWithModel(
            Path projectDir,
            List<EvalCaseLoader.LoadedCase> cases,
            AttemptArtifactWriter artifactWriter,
            EvalRunLimit runLimit,
            int concurrency,
            ScorerPipeline scorers,
            EvalResumeStore resumeStore,
            EvalBackend backend) {
        ModeLauncherRegistry registry = ModeLauncherRegistry.defaults();
        cases.forEach(evalCase -> registry.resolve(evalCase.evalCase().mode()));
        try (HeadlessAgentRuntime runtime = HeadlessAgentRuntime.create(projectDir)) {
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
                            Sandbox::of,
                            artifactWriter);
            return runCases(runner, cases, runLimit, concurrency, resumeStore, scorers);
        }
    }

    private static List<EvalCaseReport> runCases(
            EvalRunner runner,
            List<EvalCaseLoader.LoadedCase> cases,
            EvalRunLimit runLimit,
            int concurrency,
            EvalResumeStore resumeStore,
            ScorerPipeline scorers) {
        List<EvalCaseReport> reports = new java.util.ArrayList<>();
        RunMetrics accumulated = RunMetrics.ZERO;
        String scorerFingerprint = scorers.reproducibilityFingerprint();
        for (EvalCaseLoader.LoadedCase loaded : cases) {
            java.util.Optional<EvalCaseReport> resumed = resumeStore == null
                    ? java.util.Optional.empty()
                    : resumeStore.reusableCase(loaded, scorerFingerprint);
            EvalCaseReport report;
            if (resumed.isPresent()) {
                report = resumed.get();
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
        }
        return reports;
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

    private static void writeReports(ReportTargets targets, String markdown, String json) {
        try {
            Files.createDirectories(targets.runDir());
            createParent(targets.markdownOut());
            createParent(targets.jsonOut());
            Files.writeString(targets.markdownOut(), markdown);
            Files.writeString(targets.jsonOut(), json);
            System.out.println("Report written to " + targets.markdownOut());
            System.out.println("JSON report written to " + targets.jsonOut());
            System.out.println("Attempt artifacts written under " + targets.runDir());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write reports", e);
        }
    }

    private record ReportTargets(Path runDir, Path markdownOut, Path jsonOut) {
        static ReportTargets resolve(
                Path projectDir,
                Path explicitMarkdownOut,
                Path explicitJsonOut,
                Path resumeDir) {
            Path runDir = resumeDir == null
                    ? projectDir.resolve("eval/reports/run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .format(ZonedDateTime.now(ZoneId.systemDefault())))
                    : resumeDir.toAbsolutePath().normalize();
            return new ReportTargets(
                    runDir,
                    explicitMarkdownOut == null ? runDir.resolve("report.md") : explicitMarkdownOut,
                    explicitJsonOut == null ? runDir.resolve("report.json") : explicitJsonOut);
        }
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
                    backend, compareBaseline, compareCandidate);
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
        try {
            createParent(out);
            Files.writeString(out, content);
            System.out.println(successPrefix + out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + out, e);
        }
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
