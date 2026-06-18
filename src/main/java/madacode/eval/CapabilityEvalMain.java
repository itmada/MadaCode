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

        if (opts.selfTest) {
            List<EvalCaseReport> results = runSelfTest(selected);
            System.out.println(EvalReport.render(results));
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

        if (!opts.unsafeLocal) {
            System.err.println("Real-model eval currently uses LOCAL_UNSAFE isolation. "
                    + "Re-run with --unsafe-local to acknowledge that agent processes can reach the host, "
                    + "network, and repository files, including judge scripts. Treat this as a local smoke "
                    + "measurement, not a hidden-judge benchmark. Use only trusted cases.");
            System.exit(2);
        }

        List<EvalCaseReport> results = runWithModel(projectDir, selected);
        String report = EvalReport.render(results);
        System.out.println(report);
        writeReport(projectDir, opts.out, report);

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

    /** Zero-cost plumbing check: no model, no runtime — the no-op launcher leaves workspaces as-is. */
    private static List<EvalCaseReport> runSelfTest(List<EvalCaseLoader.LoadedCase> cases) {
        ModeLauncherRegistry registry = new ModeLauncherRegistry();
        cases.stream()
                .map(c -> c.evalCase().mode())
                .distinct()
                .forEach(mode -> registry.register(new NoOpModeLauncher(mode)));
        EvalRunner runner = new EvalRunner(
                null,
                registry,
                ScorerPipeline.of(new VerifyScriptScorer()));
        return runner.runAll(cases);
    }

    private static List<EvalCaseReport> runWithModel(Path projectDir, List<EvalCaseLoader.LoadedCase> cases) {
        ModeLauncherRegistry registry = ModeLauncherRegistry.defaults();
        cases.forEach(evalCase -> registry.resolve(evalCase.evalCase().mode()));
        try (HeadlessAgentRuntime runtime = HeadlessAgentRuntime.create(projectDir)) {
            EvalRunner runner = new EvalRunner(
                    runtime,
                    registry,
                    ScorerPipeline.of(new VerifyScriptScorer()));
            return runner.runAll(cases);
        }
    }

    private static void writeReport(Path projectDir, Path explicitOut, String report) {
        try {
            Path out = explicitOut;
            if (out == null) {
                Path reportsDir = projectDir.resolve("eval/reports");
                Files.createDirectories(reportsDir);
                String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(ZonedDateTime.now(ZoneId.systemDefault()));
                out = reportsDir.resolve("report-" + ts + ".md");
            }
            Files.writeString(out, report);
            System.out.println("Report written to " + out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write report", e);
        }
    }

    private record Options(
            boolean selfTest,
            boolean unsafeLocal,
            String caseId,
            String mode,
            String capability,
            Path casesDir,
            Path out) {

        static Options parse(String[] args) {
            boolean selfTest = false;
            boolean unsafeLocal = false;
            String caseId = null;
            String mode = null;
            String capability = null;
            Path casesDir = null;
            Path out = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--self-test" -> selfTest = true;
                    case "--unsafe-local" -> unsafeLocal = true;
                    case "--case" -> caseId = value(args, ++i, "--case");
                    case "--mode" -> mode = value(args, ++i, "--mode");
                    case "--capability" -> capability = value(args, ++i, "--capability");
                    case "--cases-dir" -> casesDir = Path.of(value(args, ++i, "--cases-dir"));
                    case "--out" -> out = Path.of(value(args, ++i, "--out"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(selfTest, unsafeLocal, caseId, mode, capability, casesDir, out);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
