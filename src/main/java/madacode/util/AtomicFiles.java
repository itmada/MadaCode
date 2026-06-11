package madacode.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class AtomicFiles {

    private AtomicFiles() {
    }

    @FunctionalInterface
    public interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    public static void writeAtomically(Path target, IOConsumer<Path> writer) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");

        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(parent, absoluteTarget.getFileName().toString(), ".tmp");
        try {
            writer.accept(tempFile);
            moveIntoPlace(tempFile, absoluteTarget);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public static void moveIntoPlace(Path tempFile, Path target) throws IOException {
        Objects.requireNonNull(tempFile, "tempFile");
        Objects.requireNonNull(target, "target");
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
