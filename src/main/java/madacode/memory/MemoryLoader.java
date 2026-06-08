package madacode.memory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Top-level orchestrator for context injection.
 * Assembles AGENTS.md (human-written project rules) and MEMORY.md (agent-written notes).
 */
public class MemoryLoader {

    private final AgentsMdLoader agentsMdLoader;
    private final MemoryStore memoryStore;
    private final boolean enabled;

    public MemoryLoader(AgentsMdLoader agentsMdLoader, MemoryStore memoryStore, boolean enabled) {
        this.agentsMdLoader = agentsMdLoader;
        this.memoryStore = memoryStore;
        this.enabled = enabled;
    }

    public static MemoryLoader disabled() {
        return new MemoryLoader(new AgentsMdLoader(), null, false);
    }

    public Optional<String> renderForSystemPrompt(Path cwd) {
        if (!enabled) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();

        for (var loaded : agentsMdLoader.load(cwd)) {
            sb.append("<agents-md source=\"").append(loaded.source()).append("\">\n");
            sb.append(loaded.content()).append("\n");
            sb.append("</agents-md>\n\n");
        }

        memoryStore.readIndex().ifPresent(idx -> {
            String rendered = MemoryIndex.truncate(idx);
            if (!rendered.isBlank()) {
                sb.append("<memory-index>\n");
                sb.append(rendered);
                sb.append("\n</memory-index>\n");
            }
        });

        return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString().stripTrailing());
    }
}
