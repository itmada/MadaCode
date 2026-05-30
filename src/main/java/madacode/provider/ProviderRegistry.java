package madacode.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ProviderRegistry {

    private final Map<String, Provider> byName = new LinkedHashMap<>();
    private final ProviderStateStore state;
    private volatile ActiveState active;

    /** Names reserved for {@code /provider} subcommands; users cannot name a provider these. */
    private static final java.util.Set<String> RESERVED_NAMES = java.util.Set.of("reset");

    public ProviderRegistry(List<Provider> providers, ProviderStateStore state) {
        this.state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(providers, "providers");
        if (providers.isEmpty()) {
            throw new ProviderException("ProviderRegistry requires at least one provider");
        }
        for (Provider p : providers) {
            validateName(p.name());
            byName.put(p.name(), p);
        }
        Provider initial = state.readActiveProvider()
                .map(byName::get)
                .filter(Objects::nonNull)
                .orElseGet(() -> byName.values().iterator().next());
        String initialModel = state.readActiveModel(initial.name())
                .filter(modelName -> hasModel(initial, modelName))
                .orElse(initial.defaultModel());
        this.active = buildState(initial, initialModel);
    }

    /** Test helper: single-provider registry backed by in-memory state. */
    public static ProviderRegistry singleProvider(Provider provider) {
        return new ProviderRegistry(List.of(provider), ProviderStateStore.inMemory());
    }

    public ActiveState active() {
        return active;
    }

    public List<Provider> all() {
        return List.copyOf(byName.values());
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public Optional<Provider> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public void setActiveProvider(String name) {
        setActiveProvider(name, true);
    }

    /**
     * Switch the active provider. When {@code persist} is true, the choice is
     * written to {@link ProviderStateStore} so it survives restarts.
     *
     * <p>Pass {@code false} for ephemeral switches that should not affect
     * future startups (e.g. {@code --provider} CLI flag).
     *
     * <p>The volatile {@code active} reference flip happens under {@code this} lock
     * (mutual exclusion with {@link #setActiveModel}); the {@link ProviderStateStore}
     * write is best-effort and runs outside the lock to avoid blocking concurrent
     * switches on slow disk IO.
     */
    public void setActiveProvider(String name, boolean persist) {
        Provider next = find(name).orElseThrow(
                () -> new ProviderException("Unknown provider: " + name));
        synchronized (this) {
            String modelName = state.readActiveModel(next.name())
                    .filter(saved -> hasModel(next, saved))
                    .orElse(next.defaultModel());
            this.active = buildState(next, modelName);
        }
        if (persist) {
            state.writeActiveProvider(name);
        }
    }

    /** Clears persisted active provider and resets to {@code providers[0]}. */
    public void resetActive() {
        Provider first = byName.values().iterator().next();
        synchronized (this) {
            this.active = buildState(first, first.defaultModel());
        }
        state.clearActiveProvider();
    }

    /** Switch model within the active provider and persist it for future sessions. */
    public void setActiveModel(String modelName) {
        String providerName;
        synchronized (this) {
            Provider current = active.provider();
            Model model = current.models().stream()
                    .filter(m -> m.name().equals(modelName))
                    .findFirst()
                    .orElseThrow(() -> new ProviderException(
                            "Model '" + modelName + "' not in provider '" + current.name() + "'"));
            this.active = new ActiveState(current, model);
            providerName = current.name();
        }
        state.writeActiveModel(providerName, modelName);
    }

    private static boolean hasModel(Provider provider, String modelName) {
        return provider.models().stream().anyMatch(m -> m.name().equals(modelName));
    }

    private static ActiveState buildState(Provider provider, String modelName) {
        Model model = provider.models().stream()
                .filter(m -> m.name().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new ProviderException(
                        "defaultModel '" + modelName + "' not in provider '" + provider.name() + "'"));
        return new ActiveState(provider, model);
    }

    /**
     * Adds a provider to the live registry. Does NOT change the active provider
     * and does NOT persist — callers persist via ProviderLoader separately.
     *
     * @throws ProviderException if the name is reserved or already registered
     */
    public synchronized void addProvider(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        validateName(provider.name());
        byName.put(provider.name(), provider);
    }

    /** Removes a provider by name. Used to roll back a failed persist. No-op if absent.
     *  Never removes the last provider, and never the currently active one. */
    public synchronized void removeProvider(String name) {
        if (name == null || !byName.containsKey(name)) {
            return;
        }
        if (byName.size() <= 1) {
            throw new ProviderException("cannot remove the only provider: " + name);
        }
        if (active.provider().name().equals(name)) {
            throw new ProviderException("cannot remove the active provider: " + name);
        }
        byName.remove(name);
    }

    private void validateName(String name) {
        if (RESERVED_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            throw new ProviderException("provider name '" + name
                    + "' is reserved (used by /provider subcommands)");
        }
        if (byName.containsKey(name)) {
            throw new ProviderException("duplicate provider name: " + name);
        }
    }
}
