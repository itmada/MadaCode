package madacode.eval;

import madacode.core.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryScorerTest {

    private static final Path WORKSPACE = Path.of("/tmp/eval-workspace");
    private static final RunBudget BUDGET =
            new RunBudget(1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);

    private final TrajectoryScorer scorer = new TrajectoryScorer();

    @Test
    void scorePassesForAllowedToolsWhitelistedFilesAndProvableReadBeforeEdit() {
        EvalCase evalCase = evalCase(new TrajectoryChecks(
                List.of("file_read", "edit", "write"),
                List.of("bash"),
                List.of("src/**"),
                true,
                null));
        ExecutionTrace trace = trace(
                List.of(
                        invocation("file_read", "{\"path\":\"src/App.java\"}", "1\tclass App {}", 1),
                        invocation("edit", writeInput("src/App.java"), "updated", 2),
                        invocation("write", writeInput("src/NewFile.java"), "created", 3)),
                List.of(
                        new TouchedFile("src/App.java", TouchedFile.ChangeKind.MODIFIED),
                        new TouchedFile("src/NewFile.java", TouchedFile.ChangeKind.CREATED)),
                3,
                60);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertTrue(scorer.appliesTo(evalCase));
        assertTrue(score.passed(), score.detail());
        assertTrue(score.gating());
        assertEquals("All trajectory checks passed.", score.detail());
    }

    @Test
    void scoreListsDisallowedForbiddenAndOutOfWhitelistViolations() {
        EvalCase evalCase = evalCase(new TrajectoryChecks(
                List.of("file_read"),
                List.of("bash"),
                List.of("src/**"),
                false,
                null));
        ExecutionTrace trace = trace(
                List.of(
                        invocation("bash", "{\"command\":\"touch README.md\"}", "ok", 7),
                        invocation("edit", writeInput("README.md"), "ok", 9)),
                List.of(new TouchedFile("README.md", TouchedFile.ChangeKind.MODIFIED)),
                2,
                40);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertFalse(score.passed());
        assertTrue(score.detail().contains("Unexpected tool bash at #7."), score.detail());
        assertTrue(score.detail().contains("Unexpected tool edit at #9."), score.detail());
        assertTrue(score.detail().contains("Forbidden tool bash used at #7."), score.detail());
        assertTrue(score.detail().contains("Touched file outside whitelist: README.md."), score.detail());
    }

    @Test
    void scoreFailsClosedWhenModifiedFileHasNoExplicitEditOrWriteTrace() {
        EvalCase evalCase = evalCase(new TrajectoryChecks(
                List.of(),
                List.of(),
                List.of(),
                true,
                null));
        ExecutionTrace trace = trace(
                List.of(invocation("bash", "{\"command\":\"python mutate.py\"}", "ok", 4)),
                List.of(new TouchedFile("src/App.java", TouchedFile.ChangeKind.MODIFIED)),
                1,
                20);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertFalse(score.passed());
        assertTrue(
                score.detail().contains(
                        "Modified/deleted file is unverifiable without explicit edit/write: src/App.java."),
                score.detail());
    }

    @Test
    void scoreFailsWhenReadHappensAfterTheFirstEdit() {
        EvalCase evalCase = evalCase(new TrajectoryChecks(
                List.of(),
                List.of(),
                List.of(),
                true,
                null));
        ExecutionTrace trace = trace(
                List.of(
                        invocation("edit", writeInput("src/App.java"), "updated", 2),
                        invocation("file_read", "{\"path\":\"src/App.java\"}", "1\tclass App {}", 5),
                        invocation("edit", writeInput("src/App.java"), "updated again", 6)),
                List.of(new TouchedFile("src/App.java", TouchedFile.ChangeKind.MODIFIED)),
                3,
                70);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertFalse(score.passed());
        assertTrue(
                score.detail().contains(
                        "file_read for src/App.java happened after the earliest edit/write (read #5, edit/write #2)."),
                score.detail());
    }

    private static EvalCase evalCase(TrajectoryChecks checks) {
        return new EvalCase(
                "trajectory-case",
                "Trajectory scorer test",
                "common",
                "default",
                List.of("trajectory"),
                "Test instruction",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new EvalChecks(checks, null, null, null),
                List.of());
    }

    private static ScoringContext context(ExecutionTrace trace) {
        return new ScoringContext(new TestEnvironment(WORKSPACE), trace, BUDGET);
    }

    private static ExecutionTrace trace(
            List<ToolInvocation> invocations,
            List<TouchedFile> fileEffects,
            int toolCalls,
            int totalTokens) {
        return new ExecutionTrace(
                invocations,
                fileEffects,
                List.of(),
                List.of(),
                "",
                new RunMetrics(0, 0, 0, toolCalls, new TokenUsage(totalTokens, 0, 0, 0)));
    }

    private static ToolInvocation invocation(String name, String inputJson, String resultJson, int ordinal) {
        return new ToolInvocation(name, inputJson, resultJson, ToolInvocation.Phase.WORKER, ordinal);
    }

    private static String writeInput(String relPath) {
        return "{\"file_path\":\"" + jsonEscape(WORKSPACE.resolve(relPath).toString()) + "\"}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\");
    }

    private static final class TestEnvironment implements EvalExecutionEnvironment {

        private final Path workspace;

        private TestEnvironment(Path workspace) {
            this.workspace = workspace;
        }

        @Override
        public Path workspace() {
            return workspace;
        }

        @Override
        public VerifyOutcome runVerify(RunBudget budget) {
            throw new UnsupportedOperationException("not used in scorer tests");
        }

        @Override
        public IsolationLevel isolationLevel() {
            return IsolationLevel.LOCAL_UNSAFE;
        }

        @Override
        public void close() {}
    }
}
