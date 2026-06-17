package madacode.tool;

import madacode.util.ToolNameNormalizer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

    private final Map<String, Tool<?>> tools = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public synchronized void register(Tool<?> tool) {
        String canonical = tool.name();
        tools.put(canonical, tool);
        aliases.put(canonical, canonical);

        String normalized = ToolNameNormalizer.normalize(canonical);
        if (normalized != null && !normalized.isBlank()) {
            aliases.put(normalized, canonical);
        }
        // Backward-compat alias for historical web_fetch naming.
        if ("web_fetch".equals(canonical)) {
            aliases.put("webfetch", canonical);
        }
        String configuredAlias = ToolNameCanonicalizer.canonicalize(canonical);
        if (configuredAlias != null && !configuredAlias.equals(canonical)) {
            aliases.put(configuredAlias, canonical);
        }
    }

    public synchronized void remove(String name) {
        String key = resolveCanonical(name);
        if (key == null) {
            return;
        }
        tools.remove(key);
        aliases.entrySet().removeIf(entry -> entry.getValue().equals(key));
    }

    public synchronized Optional<Tool<?>> find(String name) {
        String key = resolveCanonical(name);
        return key == null ? Optional.empty() : Optional.ofNullable(tools.get(key));
    }

    public synchronized Collection<Tool<?>> tools() {
        return List.copyOf(tools.values());
    }

    private String resolveCanonical(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        if (tools.containsKey(name)) {
            return name;
        }

        String byAlias = aliases.get(name);
        if (byAlias != null) {
            return byAlias;
        }

        String normalized = ToolNameNormalizer.normalize(name);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String byNormalized = aliases.get(normalized);
        if (byNormalized != null) {
            return byNormalized;
        }
        String canonicalized = ToolNameCanonicalizer.canonicalize(name);
        return canonicalized == null ? null : aliases.get(canonicalized);
    }
}
