package madacode.cli.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists the most recently active session ID to the current workspace bucket
 * so {@code --continue} can pick up where the user left off.
 */
public final class SessionPointer {

    private final Path file;

    public SessionPointer(Path file) {
        this.file = Objects.requireNonNull(file, "file")
                .toAbsolutePath()
                .normalize();
    }

    public Optional<String> read() {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String value = Files.readString(file).strip();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(String sessionId) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sessionId.strip());
        } catch (IOException e) {
            // best-effort
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // best-effort
        }
    }
}
