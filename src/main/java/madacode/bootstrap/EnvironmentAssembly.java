package madacode.bootstrap;

import madacode.cli.CliArgs;
import madacode.provider.ProviderLoader;
import madacode.provider.ProviderRegistry;
import madacode.provider.ProviderStateStore;
import madacode.provider.TemplateCreatedException;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiErrorClassifier;
import madacode.services.api.MadaApiClient;
import madacode.services.api.RetryOptions;
import madacode.services.api.RetryingApiClient;

import java.nio.file.Path;

final class EnvironmentAssembly {

    private EnvironmentAssembly() {
    }

    static EnvironmentRuntime create(CliArgs args, TerminalRuntime terminal) {
        ProviderStateStore stateStore = ProviderStateStore.defaultStore();
        var providers = loadProviders(terminal);
        ProviderRegistry registry = new ProviderRegistry(providers, stateStore);

        // Apply --provider CLI flag (ephemeral — does NOT persist; restart returns to state.json's value)
        String override = args.providerOverride();
        if (override != null) {
            registry.setActiveProvider(override, false);
        }

        Path homeDir = Path.of(System.getProperty("user.home"));
        Path projectDir = Path.of("").toAbsolutePath();
        boolean memoryEnabled = !args.noMemory()
                && !"true".equals(System.getenv("MADA_DISABLE_MEMORY"));
        return new EnvironmentRuntime(
                args,
                registry,
                createApiClient(registry),
                homeDir,
                projectDir,
                memoryEnabled);
    }

    private static java.util.List<madacode.provider.Provider> loadProviders(TerminalRuntime terminal) {
        ProviderLoader loader = ProviderLoader.defaultLoader();
        if (!loader.exists() && terminal.interactive()) {
            return new ProviderSetupWizard(loader, terminal).run();
        }
        try {
            return loader.load();
        } catch (TemplateCreatedException e) {
            // First-time setup: friendly exit so the user can edit the generated template.
            throw new BootstrapException(e.getMessage(), 0);
        }
    }

    private static ApiClient createApiClient(ProviderRegistry registry) {
        return new RetryingApiClient(
                new MadaApiClient(registry),
                RetryOptions.defaults(),
                new ApiErrorClassifier());
    }
}
