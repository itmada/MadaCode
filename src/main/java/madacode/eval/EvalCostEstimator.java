package madacode.eval;

import madacode.provider.Provider;
import madacode.provider.ProviderLoader;
import madacode.provider.ProviderPricing;
import madacode.storage.RuntimePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Looks up optional provider pricing and estimates report costs without affecting execution. */
public final class EvalCostEstimator {

    private static final EvalCostEstimator NONE = new EvalCostEstimator(Map.of());

    private final Map<String, ProviderPricing> pricingByProvider;

    private EvalCostEstimator(Map<String, ProviderPricing> pricingByProvider) {
        this.pricingByProvider = Map.copyOf(pricingByProvider);
    }

    public static EvalCostEstimator none() {
        return NONE;
    }

    public static EvalCostEstimator fromDefaultProviderConfig(Path projectDir) {
        Path home = Path.of(System.getProperty("user.home"));
        Path providersFile = RuntimePaths.forProject(home, projectDir).globalProvidersFile();
        if (!Files.isRegularFile(providersFile)) {
            return none();
        }
        return fromProviderFile(providersFile);
    }

    static EvalCostEstimator fromProviderFile(Path providersFile) {
        List<Provider> providers;
        try {
            providers = new ProviderLoader(providersFile).load();
        } catch (RuntimeException e) {
            return none();
        }
        Map<String, ProviderPricing> pricing = new LinkedHashMap<>();
        for (Provider provider : providers) {
            if (provider.pricing() != null) {
                pricing.put(provider.name(), provider.pricing());
            }
        }
        return pricing.isEmpty() ? none() : new EvalCostEstimator(pricing);
    }

    public Optional<EvalCostEstimate> estimate(RunMetrics metrics, EvalRunManifest manifest) {
        if (manifest == null || metrics == null || metrics.tokenUsage().total() == 0) {
            return Optional.empty();
        }
        ProviderPricing pricing = pricingByProvider.get(manifest.provider());
        return pricing == null
                ? Optional.empty()
                : Optional.of(EvalCostEstimate.of(metrics.tokenUsage(), pricing));
    }

    public Optional<EvalCostEstimate> estimateReports(List<EvalCaseReport> reports) {
        EvalCostEstimate total = null;
        for (EvalCaseReport report : reports) {
            RunMetrics metrics = report.totalMetrics();
            if (metrics.tokenUsage().total() == 0) {
                continue;
            }
            Optional<EvalCostEstimate> estimate = estimate(metrics, report.manifest());
            if (estimate.isEmpty()) {
                return Optional.empty();
            }
            total = total == null ? estimate.get() : total.plus(estimate.get());
        }
        return Optional.ofNullable(total);
    }
}
