package madacode.eval;

import madacode.core.session.ConversationSession;
import madacode.core.model.TokenUsage;
import madacode.services.api.ApiClientException;
import madacode.services.api.ApiFailureClassification;
import madacode.services.api.ApiErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void transientApiErrorOutcomeIsInfraErrorAndExcludedFromValidAttempts() throws IOException {
        EvalCaseLoader.LoadedCase loaded = loadedCase("transient-api");
        ModeLauncherRegistry registry = new ModeLauncherRegistry()
                .register(new FixedLauncher(new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.API_ERROR,
                        RunMetrics.ZERO,
                        "API_ERROR",
                        "provider overloaded",
                        "",
                        true,
                        new ApiFailureClassification(
                                ApiErrorType.SERVER_ERROR,
                                true,
                                503,
                                "provider overloaded"))));

        EvalCaseReport report = runner(registry).runCase(loaded);

        assertEquals(EvalCaseReport.GateVerdict.INFRA_ERROR, report.gateVerdict());
        assertEquals(0, report.validAttempts());
        assertEquals(1, report.infraErrors());
        EvalResult attempt = report.attempts().getFirst();
        assertEquals(EvalResult.FinalVerdict.INFRA_ERROR, attempt.verdict());
        assertEquals(EvalResult.ExecutionStatus.API_ERROR, attempt.executionStatus());
        assertTrue(attempt.detail().contains("transient infrastructure"));
    }

    @Test
    void transientApiExceptionCauseIsInfraErrorAndExcludedFromValidAttempts() throws IOException {
        EvalCaseLoader.LoadedCase loaded = loadedCase("transient-exception");
        ModeLauncherRegistry registry = new ModeLauncherRegistry()
                .register(new ThrowingLauncher(ApiClientException.http(503, "try later")));

        EvalCaseReport report = runner(registry).runCase(loaded);

        assertEquals(EvalCaseReport.GateVerdict.INFRA_ERROR, report.gateVerdict());
        assertEquals(0, report.validAttempts());
        assertEquals(EvalResult.FinalVerdict.INFRA_ERROR, report.attempts().getFirst().verdict());
        assertEquals(EvalResult.ExecutionStatus.CRASHED, report.attempts().getFirst().executionStatus());
    }

    @Test
    void ordinaryLauncherCrashRemainsMeasuredFail() throws IOException {
        EvalCaseLoader.LoadedCase loaded = loadedCase("ordinary-crash");
        ModeLauncherRegistry registry = new ModeLauncherRegistry()
                .register(new ThrowingLauncher(new RuntimeException("agent crashed")));

        EvalCaseReport report = runner(registry).runCase(loaded);

        assertEquals(EvalCaseReport.GateVerdict.FAIL, report.gateVerdict());
        assertEquals(1, report.validAttempts());
        assertEquals(0, report.infraErrors());
        EvalResult attempt = report.attempts().getFirst();
        assertEquals(EvalResult.FinalVerdict.FAIL, attempt.verdict());
        assertEquals(EvalResult.ExecutionStatus.CRASHED, attempt.executionStatus());
    }

    @Test
    void budgetLimitSkipsRemainingCasesWithoutCreatingAttempts() throws IOException {
        EvalCaseLoader.LoadedCase first = loadedCase("first");
        EvalCaseLoader.LoadedCase second = loadedCase("second");
        ModeLauncherRegistry registry = new ModeLauncherRegistry()
                .register(new FixedLauncher(new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.COMPLETED,
                        new RunMetrics(0, 0, 0, 0, new TokenUsage(0, 10, 0, 0)),
                        "COMPLETED",
                        "done",
                        "",
                        true)));

        List<EvalCaseReport> reports = runner(registry)
                .runAll(List.of(first, second), EvalRunLimit.maxTotalTokens(10));

        assertEquals(2, reports.size());
        assertEquals(EvalCaseReport.GateVerdict.PASS, reports.get(0).gateVerdict());
        assertEquals(EvalCaseReport.GateVerdict.SKIPPED, reports.get(1).gateVerdict());
        assertEquals(EvalCaseReport.PassAtKVerdict.SKIPPED, reports.get(1).passAtKVerdict());
        assertTrue(reports.get(1).skipped());
        assertTrue(reports.get(1).attempts().isEmpty());
        assertTrue(reports.get(1).skipDetail().contains("10/10"));
    }

    @Test
    void parallelAttemptsPreserveOrderAndAggregateMetrics() throws IOException {
        EvalCaseLoader.LoadedCase loaded = loadedCase("parallel", 3);
        ModeLauncherRegistry registry = new ModeLauncherRegistry()
                .register(new FixedLauncher(new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.COMPLETED,
                        new RunMetrics(0, 0, 0, 0, new TokenUsage(0, 1, 0, 0)),
                        "COMPLETED",
                        "done",
                        "",
                        true)));

        EvalCaseReport report = runner(registry).runCase(loaded, 3);

        assertEquals(3, report.attempts().size());
        assertEquals(3, report.passes());
        assertEquals(3, report.totalTokens().total());
        assertEquals(EvalCaseReport.GateVerdict.PASS, report.gateVerdict());
    }

    @Test
    void parallelAttemptsWorkForLongRunningModeWithDeterministicLauncher() throws IOException {
        EvalCaseLoader.LoadedCase loaded = loadedCase("parallel-longrun", "long-running", 3);
        ModeLauncherRegistry registry = new ModeLauncherRegistry()
                .register(new FixedLauncher("long-running", new ModeLauncher.LaunchOutcome(
                        EvalResult.ExecutionStatus.COMPLETED,
                        RunMetrics.ZERO,
                        "COMPLETED",
                        "done",
                        "",
                        true)));

        EvalCaseReport report = runner(registry).runCase(loaded, 3);

        assertEquals(3, report.attempts().size());
        assertEquals(EvalCaseReport.GateVerdict.PASS, report.gateVerdict());
    }

    private EvalRunner runner(ModeLauncherRegistry registry) {
        return new EvalRunner(null, registry, ScorerPipeline.of(new VerifyScriptScorer()));
    }

    private EvalCaseLoader.LoadedCase loadedCase(String id) throws IOException {
        return loadedCase(id, 1);
    }

    private EvalCaseLoader.LoadedCase loadedCase(String id, int samples) throws IOException {
        return loadedCase(id, "test-mode", samples);
    }

    private EvalCaseLoader.LoadedCase loadedCase(String id, String mode, int samples) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir.resolve("workspace"));
        Files.writeString(dir.resolve("verify.sh"), "exit 0\n");
        return new EvalCaseLoader.LoadedCase(evalCase(id, mode, samples), dir, "hash-" + id);
    }

    private static EvalCase evalCase(String id) {
        return evalCase(id, 1);
    }

    private static EvalCase evalCase(String id, int samples) {
        return evalCase(id, "test-mode", samples);
    }

    private static EvalCase evalCase(String id, String mode, int samples) {
        return new EvalCase(
                id,
                "desc",
                mode,
                "default",
                List.of(),
                "run",
                false,
                samples,
                1,
                1,
                1,
                30,
                30,
                1024,
                null,
                EvalChecks.NONE,
                List.of());
    }

    private record FixedLauncher(String modeId, ModeLauncher.LaunchOutcome outcome) implements ModeLauncher {
        FixedLauncher(ModeLauncher.LaunchOutcome outcome) {
            this("test-mode", outcome);
        }

        @Override
        public String modeId() {
            return modeId;
        }

        @Override
        public LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context) {
            return outcome;
        }
    }

    private record ThrowingLauncher(RuntimeException exception) implements ModeLauncher {
        @Override
        public String modeId() {
            return "test-mode";
        }

        @Override
        public LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context) {
            throw exception;
        }
    }
}
