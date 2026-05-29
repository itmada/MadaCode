package madacode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared path resolution for read/search tools.
 *
 * <p>Resolves relative paths against the working directory, normalises
 * {@code ..} traversal, and follows symlinks via {@code toRealPath()}.
 * The permission gate is the sole authority for filesystem policy — this
 * class only resolves paths and determines display bases, never rejects
 * access.
 */
public final class ReadPathPolicy {

    private ReadPathPolicy() {
    }

    /**
     * Resolves a raw path against the working directory, resolving symlinks
     * and normalising traversal.  Returns a valid result with {@code null}
     * error on success; on failure the {@code path} will be {@code null}
     * and {@code error} will describe the problem.
     *
     * <p>Empty/null paths resolve to the working directory itself.
     */
    public static ResolvedPath resolveWithinWorkingDirectory(String rawPath, Path workingDirectory, String fieldName) {
        Path normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        Path trustedWorkingDirectory = toTrustedPath(normalizedWorkingDirectory);

        if (rawPath == null || rawPath.isBlank()) {
            return ResolvedPath.valid(trustedWorkingDirectory, trustedWorkingDirectory);
        }

        Path candidate;
        try {
            Path raw = Path.of(rawPath);
            candidate = raw.isAbsolute()
                    ? raw.normalize()
                    : normalizedWorkingDirectory.resolve(raw).normalize();
        } catch (RuntimeException exception) {
            return ResolvedPath.invalid("Invalid " + fieldName + ": " + rawPath);
        }

        Path trustedCandidate = trustedPathForCandidate(candidate);

        if (candidate.startsWith(normalizedWorkingDirectory)) {
            return ResolvedPath.valid(trustedCandidate, trustedWorkingDirectory);
        }

        Path displayBase = trustedCandidate.getParent() != null
                ? trustedCandidate.getParent()
                : trustedCandidate;
        return ResolvedPath.valid(trustedCandidate, displayBase);
    }

    private static Path trustedPathForCandidate(Path candidate) {
        if (Files.exists(candidate)) {
            return toTrustedPath(candidate);
        }

        Path nearestExistingParent = candidate.getParent();
        while (nearestExistingParent != null && !Files.exists(nearestExistingParent)) {
            nearestExistingParent = nearestExistingParent.getParent();
        }
        if (nearestExistingParent == null) {
            return candidate.toAbsolutePath().normalize();
        }

        Path trustedParent = toTrustedPath(nearestExistingParent);
        Path relativeRemainder = nearestExistingParent.relativize(candidate);
        return trustedParent.resolve(relativeRemainder).normalize();
    }

    private static Path toTrustedPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    public record ResolvedPath(Path path, Path displayBase, String error) {

        private static ResolvedPath valid(Path path, Path displayBase) {
            return new ResolvedPath(path, displayBase, null);
        }

        private static ResolvedPath invalid(String error) {
            return new ResolvedPath(null, null, error);
        }

        public boolean isValid() {
            return path != null;
        }
    }
}
