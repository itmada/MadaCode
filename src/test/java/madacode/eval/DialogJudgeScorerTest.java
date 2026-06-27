package madacode.eval;

import madacode.governance.IsolationProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogJudgeScorerTest {

    private static final RunBudget TEST_BUDGET =
            new RunBudget(1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);

    @Test
    void passesWhenClarifyingQuestionIsExpectedAndObserved() {
        DialogJudgeScorer scorer = new DialogJudgeScorer();

        DimensionScore score = scorer.score(
                dialogCase(true, null, null),
                context(
                        List.of("Please fix the parser."),
                        List.of("Could you clarify which input file is failing?"),
                        "Could you clarify which input file is failing?"));

        assertEquals(EvalResult.JudgeStatus.PASS, score.status());
        assertTrue(score.detail().contains("expected=true observed=true"));
    }

    @Test
    void failsWhenExpectedClarifyingQuestionIsMissing() {
        DialogJudgeScorer scorer = new DialogJudgeScorer();

        DimensionScore score = scorer.score(
                dialogCase(true, null, null),
                context(
                        List.of("Please fix the parser."),
                        List.of("I updated the parser and tests."),
                        "I updated the parser and tests."));

        assertEquals(EvalResult.JudgeStatus.FAIL, score.status());
        assertTrue(score.detail().contains("expected=true observed=false"));
    }

    @Test
    void failsWhenClarifyingQuestionWasUnexpected() {
        DialogJudgeScorer scorer = new DialogJudgeScorer();

        DimensionScore score = scorer.score(
                dialogCase(false, null, null),
                context(
                        List.of("Please fix the parser."),
                        List.of("请问你希望我先修复解析器还是测试"),
                        "请问你希望我先修复解析器还是测试"));

        assertEquals(EvalResult.JudgeStatus.FAIL, score.status());
        assertTrue(score.detail().contains("expected=false observed=true"));
    }

    @Test
    void passesRubricAndCapturesCompleteRequest() {
        FakeJudgeClient client = new FakeJudgeClient(new DialogJudgeClient.Judgment(
                EvalResult.JudgeStatus.PASS,
                "Rubric satisfied"));
        DialogJudgeScorer scorer = new DialogJudgeScorer(client);
        ScoringContext context = context(
                List.of("User turn one", "User turn two"),
                List.of("Assistant turn one"),
                "Final answer");

        DimensionScore score = scorer.score(
                dialogCase(null, "Judge the tone and completeness.", true),
                context);

        assertEquals(EvalResult.JudgeStatus.PASS, score.status());
        assertEquals(1, client.callCount);
        assertNotNull(client.lastRequest);
        assertEquals("dialog-case", client.lastRequest.caseId());
        assertEquals("Judge the tone and completeness.", client.lastRequest.rubric());
        assertNull(client.lastRequest.expectClarifyingQuestion());
        assertEquals(context.trace().userTurns(), client.lastRequest.userTurns());
        assertEquals(context.trace().assistantTurns(), client.lastRequest.assistantTurns());
        assertEquals("Final answer", client.lastRequest.finalText());
        assertTrue(score.detail().contains("rubric: PASS"));
    }

    @Test
    void failsWhenRubricJudgeFails() {
        FakeJudgeClient client = new FakeJudgeClient(new DialogJudgeClient.Judgment(
                EvalResult.JudgeStatus.FAIL,
                "Missed the required acknowledgment"));
        DialogJudgeScorer scorer = new DialogJudgeScorer(client);

        DimensionScore score = scorer.score(
                dialogCase(null, "Judge whether the assistant acknowledged the issue.", null),
                context(List.of("User"), List.of("Assistant"), "Assistant"));

        assertEquals(EvalResult.JudgeStatus.FAIL, score.status());
        assertTrue(score.detail().contains("rubric: FAIL"));
    }

    @Test
    void returnsErrorWhenRubricJudgeErrors() {
        FakeJudgeClient client = new FakeJudgeClient(new DialogJudgeClient.Judgment(
                EvalResult.JudgeStatus.ERROR,
                "Provider unavailable"));
        DialogJudgeScorer scorer = new DialogJudgeScorer(client);

        DimensionScore score = scorer.score(
                dialogCase(null, "Judge whether the assistant followed the rubric.", null),
                context(List.of("User"), List.of("Assistant"), "Assistant"));

        assertEquals(EvalResult.JudgeStatus.ERROR, score.status());
        assertTrue(score.detail().contains("rubric: ERROR"));
    }

    @Test
    void failsClosedWhenDeterministicCheckFailsButRubricPasses() {
        FakeJudgeClient client = new FakeJudgeClient(new DialogJudgeClient.Judgment(
                EvalResult.JudgeStatus.PASS,
                "Rubric satisfied"));
        DialogJudgeScorer scorer = new DialogJudgeScorer(client);

        DimensionScore score = scorer.score(
                dialogCase(true, "Judge whether the final message is polite.", null),
                context(
                        List.of("User"),
                        List.of("I updated the requested file."),
                        "I updated the requested file."));

        assertEquals(EvalResult.JudgeStatus.FAIL, score.status());
        assertTrue(score.detail().contains("clarifying-question: FAIL"));
        assertTrue(score.detail().contains("rubric: PASS"));
    }

    @Test
    void doesNotCallClientWhenRubricIsAbsent() {
        FakeJudgeClient client = new FakeJudgeClient(new DialogJudgeClient.Judgment(
                EvalResult.JudgeStatus.PASS,
                "unused"));
        DialogJudgeScorer scorer = new DialogJudgeScorer(client);

        DimensionScore score = scorer.score(
                dialogCase(false, null, null),
                context(
                        List.of("User"),
                        List.of("I made the requested changes."),
                        "I made the requested changes."));

        assertEquals(EvalResult.JudgeStatus.PASS, score.status());
        assertEquals(0, client.callCount);
        assertFalse(score.detail().contains("rubric:"));
    }

    @Test
    void returnsErrorWhenRubricExistsWithoutClient() {
        DialogJudgeScorer scorer = new DialogJudgeScorer();

        DimensionScore score = scorer.score(
                dialogCase(null, "Judge whether the assistant is concise.", null),
                context(List.of("User"), List.of("Assistant"), "Assistant"));

        assertEquals(EvalResult.JudgeStatus.ERROR, score.status());
        assertTrue(score.detail().contains("no dialog judge client configured"));
    }

    @Test
    void reproducibilityDescriptorReportsClientConfiguration() {
        DialogJudgeScorer deterministicOnly = new DialogJudgeScorer();
        FakeJudgeClient client = new FakeJudgeClient(new DialogJudgeClient.Judgment(
                EvalResult.JudgeStatus.PASS,
                "unused"));
        DialogJudgeScorer withClient = new DialogJudgeScorer(client);

        assertTrue(deterministicOnly.reproducibilityDescriptor().contains("client=none;mode=deterministic-only"));
        assertTrue(withClient.reproducibilityDescriptor().contains(client.descriptor().fingerprint()));
    }

    private static EvalCase dialogCase(Boolean expectClarifyingQuestion, String rubric, Boolean gating) {
        return new EvalCase(
                "dialog-case",
                "Dialog scoring case",
                "common",
                "default",
                List.of("dialog"),
                "Please handle the dialog carefully.",
                false,
                1,
                1,
                1,
                1,
                60,
                60,
                2048,
                null,
                new EvalChecks(null, null, new DialogChecks(expectClarifyingQuestion, rubric, gating), null),
                List.of());
    }

    private static ScoringContext context(
            List<String> userTurns,
            List<String> assistantTurns,
            String finalText) {
        return new ScoringContext(
                new FakeEnvironment(),
                new ExecutionTrace(
                        List.of(),
                        List.of(),
                        userTurns,
                        assistantTurns,
                        finalText,
                        RunMetrics.ZERO),
                TEST_BUDGET);
    }

    private static final class FakeJudgeClient implements DialogJudgeClient {
        private final Judgment judgment;
        private final Descriptor descriptor =
                new Descriptor("fake-provider", "fake-model", 0.0, null, "dialog-judge-v1");
        private int callCount;
        private Request lastRequest;

        private FakeJudgeClient(Judgment judgment) {
            this.judgment = judgment;
        }

        @Override
        public Judgment judge(Request request) {
            callCount++;
            lastRequest = request;
            return judgment;
        }

        @Override
        public Descriptor descriptor() {
            return descriptor;
        }
    }

    private static final class FakeEnvironment implements EvalExecutionEnvironment {
        @Override
        public Path workspace() {
            return Path.of(".");
        }

        @Override
        public VerifyOutcome runVerify(RunBudget budget) {
            throw new UnsupportedOperationException("verify not used in dialog scorer tests");
        }

        @Override
        public IsolationProfile isolationProfile() {
            return IsolationProfile.localUnsafe();
        }

        @Override
        public void close() {
        }
    }
}
