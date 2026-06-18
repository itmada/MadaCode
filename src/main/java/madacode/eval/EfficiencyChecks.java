package madacode.eval;

/** Declarative upper bounds for an attempt's measured cost. */
public record EfficiencyChecks(
        Integer maxToolCalls,
        Integer maxTokens,
        Boolean gating) {

    public EfficiencyChecks {
        requirePositive("maxToolCalls", maxToolCalls);
        requirePositive("maxTokens", maxTokens);
    }

    public boolean gatingOrDefault() {
        return gating != null && gating;
    }

    private static void requirePositive(String field, Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException("checks.efficiency." + field + " must be positive");
        }
    }
}
