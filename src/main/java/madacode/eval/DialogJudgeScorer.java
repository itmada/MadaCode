package madacode.eval;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Scores dialog-specific checks using deterministic heuristics and an optional rubric judge. */
public final class DialogJudgeScorer implements Scorer {

    private static final Pattern ENGLISH_INTERROGATIVE = Pattern.compile(
            "^(?:can|could|would|will|do|does|did|are|is|am|was|were|have|has|had|may|might|shall|what|when|where|which|who|whom|whose|why|how)\\b");
    private static final Pattern ENGLISH_CLARIFYING_PROMPT = Pattern.compile(
            "^(?:please\\s+clarify|clarify)\\b");
    private static final Pattern CHINESE_INTERROGATIVE = Pattern.compile(
            "(?:请问|能否|可否|是否|什么|哪(?:个|些|里)?|怎么|如何|为什么|为何|吗|呢)");

    private final DialogJudgeClient client;
    private final String clientFingerprint;

    public DialogJudgeScorer() {
        this(null);
    }

    public DialogJudgeScorer(DialogJudgeClient client) {
        this.client = client;
        this.clientFingerprint = client == null
                ? null
                : Objects.requireNonNull(client.descriptor(), "client.descriptor()").fingerprint();
    }

    @Override
    public Dimension dimension() {
        return Dimension.DIALOG;
    }

    @Override
    public boolean appliesTo(EvalCase evalCase) {
        return evalCase.checks().dialog() != null;
    }

    @Override
    public boolean gating(EvalCase evalCase) {
        return evalCase.checks().dialog().gatingOrDefault();
    }

    @Override
    public DimensionScore score(EvalCase evalCase, ScoringContext context) {
        DialogChecks checks = evalCase.checks().dialog();
        DeterministicResult deterministic = evaluateClarifyingQuestion(
                checks.expectClarifyingQuestion(),
                context.trace().assistantTurns());
        if (checks.rubric() == null) {
            return result(evalCase, deterministic.status(), deterministic.detail());
        }
        if (client == null) {
            return result(
                    evalCase,
                    EvalResult.JudgeStatus.ERROR,
                    joinDetails(
                            deterministic.detail(),
                            "rubric: ERROR no dialog judge client configured"));
        }

        DialogJudgeClient.Judgment judgment = client.judge(new DialogJudgeClient.Request(
                evalCase.id(),
                checks.rubric(),
                checks.expectClarifyingQuestion(),
                context.trace().userTurns(),
                context.trace().assistantTurns(),
                context.trace().finalText()));

        EvalResult.JudgeStatus status = combine(deterministic, judgment);
        String detail = joinDetails(
                deterministic.detail(),
                formatJudgmentDetail(judgment));
        return result(evalCase, status, detail);
    }

    @Override
    public String reproducibilityDescriptor() {
        return dimension() + "=" + getClass().getName()
                + ";client=" + (clientFingerprint == null
                ? "none;mode=deterministic-only"
                : clientFingerprint);
    }

    static boolean containsClarifyingQuestion(String assistantTurn) {
        if (assistantTurn == null) {
            return false;
        }
        String text = assistantTurn.strip();
        if (text.isEmpty()) {
            return false;
        }
        if (text.indexOf('?') >= 0 || text.indexOf('？') >= 0) {
            return true;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        return ENGLISH_INTERROGATIVE.matcher(lower).find()
                || ENGLISH_CLARIFYING_PROMPT.matcher(lower).find()
                || CHINESE_INTERROGATIVE.matcher(text).find();
    }

    private static DeterministicResult evaluateClarifyingQuestion(
            Boolean expectClarifyingQuestion,
            List<String> assistantTurns) {
        boolean observed = assistantTurns.stream().anyMatch(DialogJudgeScorer::containsClarifyingQuestion);
        if (expectClarifyingQuestion == null) {
            return new DeterministicResult(
                    EvalResult.JudgeStatus.PASS,
                    "clarifying-question: SKIPPED observed=" + observed);
        }
        if (expectClarifyingQuestion == observed) {
            return new DeterministicResult(
                    EvalResult.JudgeStatus.PASS,
                    "clarifying-question: PASS expected="
                            + expectClarifyingQuestion
                            + " observed="
                            + observed);
        }
        return new DeterministicResult(
                EvalResult.JudgeStatus.FAIL,
                "clarifying-question: FAIL expected="
                        + expectClarifyingQuestion
                        + " observed="
                        + observed);
    }

    private static EvalResult.JudgeStatus combine(
            DeterministicResult deterministic,
            DialogJudgeClient.Judgment judgment) {
        if (judgment.status() == EvalResult.JudgeStatus.ERROR) {
            return EvalResult.JudgeStatus.ERROR;
        }
        if (deterministic.status() == EvalResult.JudgeStatus.FAIL
                || judgment.status() == EvalResult.JudgeStatus.FAIL) {
            return EvalResult.JudgeStatus.FAIL;
        }
        return EvalResult.JudgeStatus.PASS;
    }

    private static String formatJudgmentDetail(DialogJudgeClient.Judgment judgment) {
        return "rubric: " + judgment.status()
                + (judgment.rationale().isBlank() ? "" : " - " + judgment.rationale());
    }

    private static String joinDetails(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n" + second;
    }

    private record DeterministicResult(
            EvalResult.JudgeStatus status,
            String detail) {
    }
}
