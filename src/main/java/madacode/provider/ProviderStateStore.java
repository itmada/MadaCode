package madacode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists the active provider name and per-provider active model across sessions.
 *
 * <p>Mirrors {@code SkillStateStore} — JSON file, best-effort writes,
 * tolerant of missing/corrupt files.
 *
 * <p>An in-memory variant exists for tests: {@link #inMemory()} returns a
 * store backed by a volatile field instead of disk.
 */
public final class ProviderStateStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;                       // null when in-memory
    private volatile String inMemoryActiveProvider; // used when file == null
    private final Map<String, String> inMemoryActiveModels = new ConcurrentHashMap<>();

    private ProviderStateStore(Path file) {
        this.file = file;
    }

    public static ProviderStateStore forFile(Path file) {
        return new ProviderStateStore(file);
    }

    /** In-memory store for tests. */
    public static ProviderStateStore inMemory() {
        return new ProviderStateStore(null);
    }

    public Optional<String> readActiveProvider() {
        if (file == null) {
            return Optional.ofNullable(inMemoryActiveProvider);
        }
        return readRoot()
                .flatMap(root -> textField(root, "activeProvider"));
    }

    public Optional<String> readActiveModel(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return Optional.empty();
        }
        if (file == null) {
            return Optional.ofNullable(inMemoryActiveModels.get(providerName));
        }
        return readRoot().flatMap(root -> {
            var activeModels = root.path("activeModels");
            if (!activeModels.isObject()) {
                return Optional.empty();
            }
            return textField(activeModels, providerName);
        });
    }

    public void writeActiveProvider(String providerName) {
        if (file == null) {
            this.inMemoryActiveProvider = providerName;
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = readRoot()
                    .filter(JsonNode::isObject)
                    .map(ObjectNode.class::cast)
                    .orElseGet(MAPPER::createObjectNode);
            root.put("activeProvider", providerName);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // best-effort
        }
    }

    public void writeActiveModel(String providerName, String modelName) {
        if (providerName == null || providerName.isBlank()
                || modelName == null || modelName.isBlank()) {
            return;
        }
        if (file == null) {
            inMemoryActiveModels.put(providerName, modelName);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = readRoot()
                    .filter(JsonNode::isObject)
                    .map(ObjectNode.class::cast)
                    .orElseGet(MAPPER::createObjectNode);
            ObjectNode activeModels;
            if (root.path("activeModels").isObject()) {
                activeModels = (ObjectNode) root.path("activeModels");
            } else {
                activeModels = MAPPER.createObjectNode();
                root.set("activeModels", activeModels);
            }
            activeModels.put(providerName, modelName);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // best-effort
        }
    }

    private Optional<JsonNode> readRoot() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readTree(file.toFile()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> textField(JsonNode root, String field) {
        if (root == null) {
            return Optional.empty();
        }
        var v = root.path(field);
        return v.isTextual() && !v.asText().isBlank()
                ? Optional.of(v.asText())
                : Optional.empty();
    }

    public void clearActiveProvider() {
        if (file == null) {
            this.inMemoryActiveProvider = null;
            inMemoryActiveModels.clear();
            return;
        }
        try {
            if (Files.isRegularFile(file)) {
                Files.delete(file);
            }
        } catch (IOException e) {
            // best-effort
        }
    }
}
