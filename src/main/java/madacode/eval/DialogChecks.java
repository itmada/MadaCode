package madacode.eval;

/** Rubric for judging the interaction rather than only the resulting workspace. */
public record DialogChecks(
        Boolean expectClarifyingQuestion,
        String rubric,
        Boolean gating) {

    public DialogChecks {
        rubric = rubric == null ? null : rubric.strip();
        if (rubric != null && rubric.isBlank()) {
            rubric = null;
        }
        if (expectClarifyingQuestion == null && rubric == null) {
            throw new IllegalArgumentException(
                    "checks.dialog must declare expectClarifyingQuestion or rubric");
        }
    }

    public boolean gatingOrDefault() {
        return gating != null && gating;
    }
}
