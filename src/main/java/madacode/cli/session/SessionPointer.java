package madacode.cli.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the most recently active session ID to {@code ~/.mada/last-session}
 * so {@code --continue} can pick up where the user left off.
 */
public final class SessionPointer {

    private static final Path PATH =
            Path.of(System.getProperty("user.home"), ".mada", "last-session");

    private SessionPointer() {
    }

    public static Optional<String> read() {
        try {
            if (!Files.isRegularFile(PATH)) {
                return Optional.empty();
            }
            String value = Files.readString(PATH).strip();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static void write(String sessionId) {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, sessionId.strip());
        } catch (IOException e) {
            // best-effort
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(PATH);
        } catch (IOException e) {
            // best-effort
        }
    }
}
