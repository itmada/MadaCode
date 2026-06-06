package madacode.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class ModelResponseLogWriter {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final Path directory;

    public ModelResponseLogWriter(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    public boolean isEnabled() {
        return DiagnosticEventLogger.isModelResponseFullLoggingEnabled();
    }

    public Path write(String model, int statusCode, Collection<String> responseLines) {
        Collection<String> lines = responseLines == null ? java.util.List.of() : responseLines;
        String body = String.join(System.lineSeparator(), lines);
        try {
            Files.createDirectories(directory);
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                    .replace(':', '-');
            long sequence = SEQUENCE.incrementAndGet();
            String safeModel = safeFilename(model == null || model.isBlank() ? "unknown-model" : model);
            Path target = directory.resolve("%s-%06d-status%d-%s.sse"
                    .formatted(timestamp, sequence, statusCode, safeModel));
            Files.writeString(target, body,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeFilename(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return sanitized.isBlank() ? "unknown-model" : sanitized;
    }
}
