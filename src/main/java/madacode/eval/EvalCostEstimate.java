package madacode.eval;

import madacode.core.model.TokenUsage;
import madacode.provider.ProviderPricing;

/** Approximate model cost derived from token usage and optional provider pricing. */
public record EvalCostEstimate(
        TokenUsage tokenUsage,
        double inputUsd,
        double outputUsd,
        double totalUsd) {

    public static EvalCostEstimate of(TokenUsage usage, ProviderPricing pricing) {
        TokenUsage safe = usage == null ? TokenUsage.ZERO : usage;
        double inputBillableTokens = safe.inputTokens()
                + (safe.cacheCreationTokens() * 1.25)
                + (safe.cacheReadTokens() * 0.10);
        double inputUsd = inputBillableTokens * pricing.inputUsdPerMillion() / 1_000_000.0;
        double outputUsd = safe.outputTokens() * pricing.outputUsdPerMillion() / 1_000_000.0;
        return new EvalCostEstimate(safe, inputUsd, outputUsd, inputUsd + outputUsd);
    }

    public EvalCostEstimate plus(EvalCostEstimate other) {
        if (other == null) {
            return this;
        }
        return new EvalCostEstimate(
                tokenUsage.plus(other.tokenUsage),
                inputUsd + other.inputUsd,
                outputUsd + other.outputUsd,
                totalUsd + other.totalUsd);
    }
}
