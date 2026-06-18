package madacode.eval;

import java.util.ArrayList;
import java.util.List;

/** Scores tool-call and token-budget assertions against aggregated run metrics. */
public final class EfficiencyScorer implements Scorer {

    @Override
    public Dimension dimension() {
        return Dimension.EFFICIENCY;
    }

    @Override
    public boolean appliesTo(EvalCase evalCase) {
        return evalCase.checks().efficiency() != null;
    }

    @Override
    public boolean gating(EvalCase evalCase) {
        return evalCase.checks().efficiency().gatingOrDefault();
    }

    @Override
    public DimensionScore score(EvalCase evalCase, ScoringContext context) {
        EfficiencyChecks checks = evalCase.checks().efficiency();
        RunMetrics metrics = context.trace().metrics();
        List<String> violations = new ArrayList<>();

        if (checks.maxToolCalls() != null && metrics.toolCalls() > checks.maxToolCalls()) {
            violations.add("Tool-call budget exceeded: "
                    + metrics.toolCalls()
                    + " > "
                    + checks.maxToolCalls()
                    + ".");
        }
        int totalTokens = metrics.tokenUsage().total();
        if (checks.maxTokens() != null && totalTokens > checks.maxTokens()) {
            violations.add("Token budget exceeded: " + totalTokens + " > " + checks.maxTokens() + ".");
        }

        return result(
                evalCase,
                violations.isEmpty() ? EvalResult.JudgeStatus.PASS : EvalResult.JudgeStatus.FAIL,
                violations.isEmpty() ? "All efficiency checks passed." : String.join("\n", violations));
    }
}
