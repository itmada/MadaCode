package madacode.eval;

import madacode.core.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EfficiencyScorerTest {

    private static final Path WORKSPACE = Path.of("/tmp/eval-workspace");
    private static final RunBudget BUDGET =
            new RunBudget(1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);

    private final EfficiencyScorer scorer = new EfficiencyScorer();

    @Test
    void scorePassesWhenBudgetsAreRespected() {
        EvalCase evalCase = evalCase(new EfficiencyChecks(3, 100, null));
        ExecutionTrace trace = trace(3, 100);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertTrue(scorer.appliesTo(evalCase));
        assertTrue(score.passed(), score.detail());
        assertFalse(score.gating());
        assertEquals("All efficiency checks passed.", score.detail());
    }

    @Test
    void scoreFailsWhenToolCallBudgetIsExceeded() {
        EvalCase evalCase = evalCase(new EfficiencyChecks(2, null, Boolean.TRUE));
        ExecutionTrace trace = trace(3, 50);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertFalse(score.passed());
        assertTrue(score.gating());
        assertTrue(score.detail().contains("Tool-call budget exceeded: 3 > 2."), score.detail());
    }

    @Test
    void scoreFailsWhenTokenBudgetIsExceeded() {
        EvalCase evalCase = evalCase(new EfficiencyChecks(null, 99, null));
        ExecutionTrace trace = trace(1, 100);

        DimensionScore score = scorer.score(evalCase, context(trace));

        assertFalse(score.passed());
        assertTrue(score.detail().contains("Token budget exceeded: 100 > 99."), score.detail());
    }

    private static EvalCase evalCase(EfficiencyChecks checks) {
        return new EvalCase(
                "efficiency-case",
                "Efficiency scorer test",
                "common",
                "default",
                List.of("efficiency"),
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
                new EvalChecks(null, checks, null, null),
                List.of());
    }

    private static ScoringContext context(ExecutionTrace trace) {
        return new ScoringContext(new TestEnvironment(WORKSPACE), trace, BUDGET);
    }

    private static ExecutionTrace trace(int toolCalls, int totalTokens) {
        return new ExecutionTrace(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                new RunMetrics(0, 0, 0, toolCalls, new TokenUsage(totalTokens, 0, 0, 0)));
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
