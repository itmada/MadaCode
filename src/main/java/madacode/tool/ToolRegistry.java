package madacode.tool;

import madacode.util.ToolNameNormalizer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

    /**
     * Aliases for standard Claude Code tool names that do <em>not</em> reduce to
     * this project's canonical names via {@link ToolNameNormalizer} (which only
     * normalizes case / camelCase / hyphens). Most standard names already match
     * after normalization ({@code Bash}→bash, {@code Edit}→edit, {@code Write}→write,
     * {@code TodoWrite}→todo_write, {@code WebFetch}→web_fetch, …); only the ones
     * whose canonical name differs semantically need an explicit mapping.
     *
     * <p>The alias is only installed when the target tool is actually registered.
     */
    private static final Map<String, String> STANDARD_NAME_ALIASES = Map.of(
            "read", "file_read",   // Claude Code "Read"
            "task", "agent"        // Claude Code "Task" (subagent)
    );

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
        // Standard Claude Code names whose canonical form differs (e.g. Read→file_read).
        STANDARD_NAME_ALIASES.forEach((alias, target) -> {
            if (target.equals(canonical)) {
                aliases.put(alias, canonical);
            }
        });
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
        return aliases.get(normalized);
    }
}
