package madacode.core.model;

/**
 * Token accounting for one model exchange.
 *
 * <p>Mirrors the {@code usage} object returned by the Anthropic Messages API:
 * <ul>
 *   <li>{@link #inputTokens} — fresh prompt tokens billed at input rate</li>
 *   <li>{@link #outputTokens} — generated tokens billed at output rate</li>
 *   <li>{@link #cacheCreationTokens} — tokens written to the prompt cache
 *       (billed at a 25% premium over input)</li>
 *   <li>{@link #cacheReadTokens} — tokens served from the prompt cache
 *       (billed at 10% of input)</li>
 * </ul>
 *
 * <p>Use {@link #plus(TokenUsage)} to aggregate usage across the iterations
 * of a single user turn — the agent loop may make several API calls per
 * turn (one per round of tool use), and the user wants the total.
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationTokens,
        int cacheReadTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        if (other == null || other == ZERO) {
            return this;
        }
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheCreationTokens + other.cacheCreationTokens,
                cacheReadTokens + other.cacheReadTokens);
    }

    public int total() {
        return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
    }
}
