package madacode.tool;

import java.nio.file.Path;

/**
 * Centralized path safety checks for tools that write to the filesystem.
 *
 * <p>Every write-path tool (FileWriteTool, FileEditTool, future NotebookEdit, etc.)
 * must go through the same baseline validation: reject blank/relative paths and
 * normalise away {@code ..} traversal.
 *
 * <p>Directory-level restrictions are handled by the permission gate via
 * {@link madacode.permission.FilesystemScope} and its associated rules —
 * tools must never reject accesses themselves.
 */
public final class PathSafety {

    private PathSafety() {
    }

    /**
     * Baseline validation for any write-target path.
     * Rejects blank and relative paths, normalizes the result.
     */
    public static SafePathResult validateForWrite(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return SafePathResult.invalid("Missing required field: file_path");
        }

        Path path = Path.of(rawPath);
        if (!path.isAbsolute()) {
            return SafePathResult.invalid(
                    "file_path must be absolute, got: " + rawPath);
        }

        return SafePathResult.valid(path.normalize());
    }

    public record SafePathResult(Path path, String error) {

        private static SafePathResult valid(Path path) {
            return new SafePathResult(path, null);
        }

        private static SafePathResult invalid(String error) {
            return new SafePathResult(null, error);
        }

        public boolean isValid() {
            return path != null;
        }
    }
}
