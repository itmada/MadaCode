package madacode.tool;

import java.nio.file.Path;

/**
 * Centralized path safety checks for tools that write to the filesystem.
 *
 * <p>Every write-path tool (FileWriteTool, FileEditTool, future NotebookEdit, etc.)
 * must go through the same baseline validation: reject blank/relative paths,
 * normalize away {@code ..} traversal, and optionally enforce a working-directory
 * sandbox.
 *
 * <p>Directory-level restrictions are handled separately by the permission system
 * ({@link DefaultPermissionGate}) rather than hard-coded here.
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

    /**
     * Optional sandbox check: rejects paths outside {@code workingDirectory}.
     * Call after {@link #validateForWrite} when you want to confine writes to a
     * specific directory tree.
     */
    public static SafePathResult enforceSandbox(Path normalizedPath, Path workingDirectory) {
        if (workingDirectory != null && !normalizedPath.startsWith(workingDirectory)) {
            return SafePathResult.invalid(
                    "file_path is outside the working directory: " + normalizedPath);
        }
        return SafePathResult.valid(normalizedPath);
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
