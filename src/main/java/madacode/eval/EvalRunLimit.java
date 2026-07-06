package madacode.eval;

/** Optional run-level budget controls for the sequential eval runner. */
public record EvalRunLimit(Long maxTotalTokens) {

    public static final EvalRunLimit NONE = new EvalRunLimit(null);

    public EvalRunLimit {
        if (maxTotalTokens != null && maxTotalTokens <= 0) {
            throw new IllegalArgumentException("maxTotalTokens must be positive");
        }
    }

    public static EvalRunLimit maxTotalTokens(long maxTotalTokens) {
        return new EvalRunLimit(maxTotalTokens);
    }

    public boolean shouldSkipNextCase(RunMetrics accumulated) {
        return maxTotalTokens != null
                && totalTokens(accumulated) >= maxTotalTokens;
    }

    public String skipDetail(RunMetrics accumulated) {
        if (maxTotalTokens == null) {
            return "";
        }
        return "run token budget reached: "
                + totalTokens(accumulated)
                + "/"
                + maxTotalTokens
                + " total tokens already used";
    }

    private static int totalTokens(RunMetrics metrics) {
        return metrics == null || metrics.tokenUsage() == null
                ? 0
                : metrics.tokenUsage().total();
    }
}
