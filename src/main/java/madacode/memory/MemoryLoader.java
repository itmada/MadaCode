package madacode.memory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Top-level orchestrator for context injection.
 * Assembles mada.md (human-written project rules) and MEMORY.md (agent-written notes).
 */
public class MemoryLoader {

    private final MadaMdLoader madaMdLoader;
    private final MemoryStore memoryStore;
    private final boolean enabled;

    public MemoryLoader(MadaMdLoader madaMdLoader, MemoryStore memoryStore, boolean enabled) {
        this.madaMdLoader = madaMdLoader;
        this.memoryStore = memoryStore;
        this.enabled = enabled;
    }

    public static MemoryLoader disabled() {
        return new MemoryLoader(new MadaMdLoader(), null, false);
    }

    public Optional<String> renderForSystemPrompt(Path cwd) {
        if (!enabled) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();

        for (var loaded : madaMdLoader.load(cwd)) {
            sb.append("<mada-md source=\"").append(loaded.source()).append("\">\n");
            sb.append(loaded.content()).append("\n");
            sb.append("</mada-md>\n\n");
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
