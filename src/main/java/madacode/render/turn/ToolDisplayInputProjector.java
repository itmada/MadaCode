package madacode.render.turn;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.util.ToolNameNormalizer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

final class ToolDisplayInputProjector {

    private final Supplier<Path> workingDirectory;

    ToolDisplayInputProjector(Supplier<Path> workingDirectory) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    ObjectNode project(String toolName, ObjectNode input) {
        if (input == null) {
            return null;
        }
        ObjectNode displayInput = input.deepCopy();
        Path root = normalizedWorkingDirectory();
        if (root == null) {
            return displayInput;
        }

        switch (normalize(toolName)) {
            case "file_read" -> relativizeField(displayInput, root, "path");
            case "file_write", "file_edit" -> relativizeField(displayInput, root, "file_path");
            case "grep" -> relativizeField(displayInput, root, "path");
            default -> { }
        }
        return displayInput;
    }

    private Path normalizedWorkingDirectory() {
        Path root = workingDirectory.get();
        if (root == null) {
            return null;
        }
        return root.toAbsolutePath().normalize();
    }

    private static void relativizeField(ObjectNode input, Path root, String field) {
        String value = input.path(field).asText("");
        if (value.isBlank()) {
            return;
        }
        String display = displayPath(root, value);
        if (!display.equals(value)) {
            input.put(field, display);
        }
    }

    private static String displayPath(Path root, String value) {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                return value;
            }
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) {
                return value;
            }
            Path relative = root.relativize(normalized);
            return relative.toString().isBlank() ? "." : relative.toString();
        } catch (InvalidPathException exception) {
            return value;
        }
    }

    private static String normalize(String toolName) {
        String normalized = ToolNameNormalizer.normalize(toolName);
        if (normalized == null || normalized.isBlank()) {
            return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }
}
