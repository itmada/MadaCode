package madacode.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Composes multiple {@link AgentLoader}s into a single lookup table.
 *
 * <p>Load order = priority order: the last loader's agents override earlier
 * ones with the same name. Recommended wiring:
 * {@code BuiltInAgentLoader -> user dir -> project dir}, so project-level
 * customizations take precedence.
 *
 * <p>Names are compared case-insensitively using {@link Locale#ROOT}.
 *
 * <p>The instance is empty until {@link #reload()} is called. Prefer
 * {@link #loaded(AgentLoader...)} which constructs and reloads in one step.
 */
public final class AgentRegistry {

    private final AgentLoader[] loaders;
    private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();

    public AgentRegistry(AgentLoader... loaders) {
        this.loaders = loaders;
    }

    /** Constructs the registry and immediately calls {@link #reload()}. */
    public static AgentRegistry loaded(AgentLoader... loaders) {
        AgentRegistry registry = new AgentRegistry(loaders);
        registry.reload();
        return registry;
    }

    public synchronized void reload() {
        agents.clear();
        for (AgentLoader loader : loaders) {
            for (AgentDefinition definition : loader.load()) {
                agents.put(key(definition.agentType()), definition);
            }
        }
    }

    public synchronized Optional<AgentDefinition> findByType(String type) {
        if (type == null || type.isBlank()) return Optional.empty();
        return Optional.ofNullable(agents.get(key(type.strip())));
    }

    public synchronized List<AgentDefinition> all() {
        return List.copyOf(agents.values());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
