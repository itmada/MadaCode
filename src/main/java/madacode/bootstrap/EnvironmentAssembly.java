package madacode.bootstrap;

import madacode.cli.CliArgs;
import madacode.provider.ProviderLoader;
import madacode.provider.ProviderRegistry;
import madacode.provider.ProviderStateStore;
import madacode.provider.TemplateCreatedException;
import madacode.events.AppEventPublisher;
import madacode.logging.DefaultDiagnosticEvents;
import madacode.logging.DiagnosticEvents;
import madacode.logging.ModelResponseLogWriter;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiErrorClassifier;
import madacode.services.api.MadaApiClient;
import madacode.services.api.RetryOptions;
import madacode.services.api.RetryingApiClient;
import madacode.storage.RuntimePaths;

import java.nio.file.Path;
import java.util.Objects;

final class EnvironmentAssembly {

    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_000;
    private static final int MAX_OUTPUT_TOKENS_UPPER_LIMIT = 64_000;
    private static final String MAX_OUTPUT_TOKENS_ENV = "MADA_MAX_OUTPUT_TOKENS";
    private static final String MANAGED_DEBUG_DIR_PROPERTY = "madacode.managed.MADA_DEBUG_DIR";

    private EnvironmentAssembly() {
    }

    static RuntimePaths pathsForCurrentProject() {
        Path homeDir = Path.of(System.getProperty("user.home"));
        Path projectDir = Path.of("").toAbsolutePath();
        return RuntimePaths.forProject(homeDir, projectDir);
    }

    static void configureEarlyLogPaths(RuntimePaths paths) {
        if (firstNonBlank(System.getenv("MADA_DEBUG_DIR")) != null) {
            return;
        }
        String current = System.getProperty("MADA_DEBUG_DIR");
        String managed = System.getProperty(MANAGED_DEBUG_DIR_PROPERTY);
        if (current == null || Objects.equals(current, managed)) {
            String next = paths.workspaceDebugDir().toString();
            System.setProperty("MADA_DEBUG_DIR", next);
            System.setProperty(MANAGED_DEBUG_DIR_PROPERTY, next);
        }
    }

    static EnvironmentRuntime create(
            CliArgs args,
            TerminalRuntime terminal,
            RuntimePaths paths,
            AppEventPublisher publisher) {
        ProviderStateStore stateStore = ProviderStateStore.forFile(paths.globalStateFile());
        ProviderLoader loader = new ProviderLoader(paths.globalProvidersFile());
        var providers = loadProviders(loader, terminal);
        ProviderRegistry registry = new ProviderRegistry(providers, stateStore);

        // Apply --provider CLI flag (ephemeral — does NOT persist; restart returns to state.json's value)
        String override = args.providerOverride();
        if (override != null) {
            registry.setActiveProvider(override, false);
        }

        boolean memoryEnabled = !args.noMemory();
        DiagnosticEvents diagnosticEvents = new DefaultDiagnosticEvents(publisher);
        return new EnvironmentRuntime(
                args,
                registry,
                loader,
                createApiClient(registry, paths, diagnosticEvents),
                paths.homeDir(),
                paths.projectDir(),
                paths,
                memoryEnabled,
                diagnosticEvents);
    }

    private static java.util.List<madacode.provider.Provider> loadProviders(
            ProviderLoader loader, TerminalRuntime terminal) {
        if (!loader.exists()) {
            return new ProviderSetupWizard(loader, terminal).run();
        }
        try {
            return loader.load();
        } catch (TemplateCreatedException e) {
            // First-time setup: friendly exit so the user can edit the generated template.
            throw new BootstrapException(e.getMessage(), 0);
        }
    }

    private static ApiClient createApiClient(
            ProviderRegistry registry,
            RuntimePaths paths,
            DiagnosticEvents diagnosticEvents) {
        return new RetryingApiClient(
                new MadaApiClient(
                        registry,
                        new ModelResponseLogWriter(modelResponseLogDir(paths)),
                        resolveMaxOutputTokens(),
                        diagnosticEvents),
                RetryOptions.defaults(),
                new ApiErrorClassifier(),
                diagnosticEvents);
    }

    private static int resolveMaxOutputTokens() {
        String configured = firstNonBlank(
                System.getenv(MAX_OUTPUT_TOKENS_ENV),
                System.getProperty(MAX_OUTPUT_TOKENS_ENV));
        if (configured == null) {
            return DEFAULT_MAX_OUTPUT_TOKENS;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed <= 0) {
                return DEFAULT_MAX_OUTPUT_TOKENS;
            }
            return Math.min(parsed, MAX_OUTPUT_TOKENS_UPPER_LIMIT);
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_OUTPUT_TOKENS;
        }
    }

    private static Path modelResponseLogDir(RuntimePaths paths) {
        String configured = firstNonBlank(
                System.getenv("MADA_MODEL_RESPONSE_LOG_DIR"),
                System.getProperty("MADA_MODEL_RESPONSE_LOG_DIR"));
        return configured == null ? paths.workspaceModelResponsesDir() : Path.of(configured);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
