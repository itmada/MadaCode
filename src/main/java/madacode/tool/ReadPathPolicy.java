package madacode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared path policy for read/search tools.
 *
 * <p>These tools advertise that they operate relative to, or under, the
 * working directory. Centralising resolution here keeps that contract
 * consistent across file reads and filesystem searches.
 */
public final class ReadPathPolicy {

    private ReadPathPolicy() {
    }

    public static ResolvedPath resolveWithinWorkingDirectory(String rawPath, Path workingDirectory, String fieldName) {
        return resolveWithinWorkingDirectory(rawPath, workingDirectory, List.of(), fieldName);
    }

    public static ResolvedPath resolveWithinWorkingDirectory(
            String rawPath, Path workingDirectory,
            List<Path> additionalTrustedRoots, String fieldName) {
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

        if (candidate.startsWith(normalizedWorkingDirectory)) {
            Path trustedCandidate = trustedPathForCandidate(candidate);
            if (!trustedCandidate.startsWith(trustedWorkingDirectory)) {
                return ResolvedPath.invalid(fieldName + " resolves outside the working directory: " + trustedCandidate);
            }
            return ResolvedPath.valid(trustedCandidate, trustedWorkingDirectory);
        }

        for (Path root : additionalTrustedRoots) {
            Path trustedRoot;
            try {
                trustedRoot = root.toRealPath();
            } catch (IOException ignored) {
                trustedRoot = root.toAbsolutePath().normalize();
            }
            Path trustedCandidate = trustedPathForCandidate(candidate);
            if (trustedCandidate.startsWith(trustedRoot)) {
                return ResolvedPath.valid(trustedCandidate, trustedRoot);
            }
        }

        return ResolvedPath.invalid(fieldName + " is outside the working directory: " + candidate);
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
