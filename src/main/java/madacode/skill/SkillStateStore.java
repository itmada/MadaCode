package madacode.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists the disabled skill blacklist to a JSON file.
 *
 * <p>File format: {@code {"disabled": ["skill-a", "skill-b"]}}
 * Only stores disabled names — new skills are auto-enabled.
 */
public final class SkillStateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Set<String> disabled = Collections.synchronizedSet(new HashSet<>());

    public SkillStateStore(Path file) {
        this.file = file;
    }

    public synchronized Set<String> load() {
        if (!Files.exists(file)) return Set.copyOf(disabled);
        try {
            var root = MAPPER.readTree(file.toFile());
            var arr = root.path("disabled");
            if (arr.isArray()) {
                for (var node : arr) {
                    disabled.add(node.asText());
                }
            }
        } catch (IOException ignored) {
            // best-effort; file corruption → disabled stays empty
        }
        return Set.copyOf(disabled);
    }

    public synchronized void disable(String name) {
        disabled.add(name);
        save();
    }

    public synchronized void enable(String name) {
        disabled.remove(name);
        save();
    }

    public boolean isDisabled(String name) {
        return disabled.contains(name);
    }

    public Set<String> disabledNames() {
        return Set.copyOf(disabled);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode arr = MAPPER.createArrayNode();
            disabled.forEach(arr::add);
            root.set("disabled", arr);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException ignored) {
            // best-effort; don't break the application for a file write failure
        }
    }
}
