package madacode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the active provider name across sessions.
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
    private volatile String inMemoryActive;         // used when file == null

    private ProviderStateStore(Path file) {
        this.file = file;
    }

    public static ProviderStateStore forFile(Path file) {
        return new ProviderStateStore(file);
    }

    public static ProviderStateStore defaultStore() {
        return forFile(Path.of(System.getProperty("user.home"), ".mada", "state.json"));
    }

    /** In-memory store for tests. */
    public static ProviderStateStore inMemory() {
        return new ProviderStateStore(null);
    }

    public Optional<String> readActiveProvider() {
        if (file == null) {
            return Optional.ofNullable(inMemoryActive);
        }
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            var root = MAPPER.readTree(file.toFile());
            var v = root.path("activeProvider");
            return v.isTextual() && !v.asText().isBlank()
                    ? Optional.of(v.asText())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void writeActiveProvider(String providerName) {
        if (file == null) {
            this.inMemoryActive = providerName;
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            root.put("activeProvider", providerName);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // best-effort
        }
    }

    public void clearActiveProvider() {
        if (file == null) {
            this.inMemoryActive = null;
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
