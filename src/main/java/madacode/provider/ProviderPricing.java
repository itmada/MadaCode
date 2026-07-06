package madacode.provider;

/** Optional provider pricing used only for reporting approximate eval cost. */
public record ProviderPricing(
        double inputUsdPerMillion,
        double outputUsdPerMillion) {

    public ProviderPricing {
        requireNonNegativeFinite(inputUsdPerMillion, "inputUsdPerMillion");
        requireNonNegativeFinite(outputUsdPerMillion, "outputUsdPerMillion");
    }

    private static void requireNonNegativeFinite(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be a finite non-negative number");
        }
    }
}
