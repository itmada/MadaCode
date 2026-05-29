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
            if (RESERVED_NAMES.contains(p.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new ProviderException("provider name '" + p.name()
                        + "' is reserved (used by /provider subcommands)");
            }
            if (byName.containsKey(p.name())) {
                throw new ProviderException("duplicate provider name: " + p.name());
            }
            byName.put(p.name(), p);
        }
        Provider initial = state.readActiveProvider()
                .map(byName::get)
                .filter(Objects::nonNull)
                .orElseGet(() -> byName.values().iterator().next());
        this.active = buildState(initial, initial.defaultModel());
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
            this.active = buildState(next, next.defaultModel());
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

    /** Switch model within the active provider. Does not persist. */
    public synchronized void setActiveModel(String modelName) {
        Provider current = active.provider();
        Model model = current.models().stream()
                .filter(m -> m.name().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new ProviderException(
                        "Model '" + modelName + "' not in provider '" + current.name() + "'"));
        this.active = new ActiveState(current, model);
    }

    private static ActiveState buildState(Provider provider, String modelName) {
        Model model = provider.models().stream()
                .filter(m -> m.name().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new ProviderException(
                        "defaultModel '" + modelName + "' not in provider '" + provider.name() + "'"));
        return new ActiveState(provider, model);
    }
}
