package madacode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-function utility for the permission gate's filesystem-scope checks.
 *
 * <p>The gate is the <em>sole authority</em> for filesystem policy — tools
 * must never reject accesses themselves.  This class centralises the two
 * scope predicates that the gate's rules consult:
 *
 * <ol>
 *   <li>{@link #withinRoots} — is a path inside the working directory
 *       (or one of the additional trusted roots)?</li>
 *   <li>{@link #isDangerousEditTarget} — does a path name a sensitive
 *       dotfile or directory that should never be silently modified?</li>
 * </ol>
 */
public final class FilesystemScope {

    private FilesystemScope() {}

    private static final Set<String> DANGEROUS_FILENAMES = Set.of(
            ".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile",
            ".gitconfig", ".gitmodules", ".mcp.json", ".claude.json",
            ".ripgreprc"
    );

    private static final Set<String> DANGEROUS_DIRECTORY_SEGMENTS = Set.of(
            ".git", ".ssh", ".vscode", ".idea", ".claude"
    );

    /**
     * Returns {@code true} when {@code rawPath} resolves to a location
     * inside {@code workingDir} or one of the {@code trustedRoots}.
     *
     * <p>Resolution follows the same logic as {@code ReadPathPolicy}:
     * <ul>
     *   <li>Null/blank paths resolve to {@code workingDir}.</li>
     *   <li>Relative paths are resolved against {@code workingDir}.</li>
     *   <li>After normalisation, {@code toRealPath()} follows symlinks
     *       for both the candidate and the working directory to prevent
     *       symlink-escape attacks.</li>
     *   <li>If the real path cannot be determined (file doesn't exist),
     *       the nearest existing ancestor's real path is used to verify
     *       scope, then the remaining relative path is appended.</li>
     * </ul>
     *
     * <p>On Windows, path comparison is case-insensitive.
     */
    public static boolean withinRoots(String rawPath, Path workingDir, List<Path> trustedRoots) {
        Path normalizedWorkingDir = workingDir.toAbsolutePath().normalize();

        if (rawPath == null || rawPath.isBlank()) {
            return true;
        }

        Path candidate;
        try {
            Path raw = Path.of(rawPath);
            candidate = raw.isAbsolute()
                    ? raw.normalize()
                    : normalizedWorkingDir.resolve(raw).normalize();
        } catch (RuntimeException e) {
            return false;
        }

        // A path is within a root iff its real (symlink-resolved) location is
        // under that root's real location. Resolving both sides handles symlink
        // escapes (candidate links out) and symlinked working directories alike,
        // and is computed once for the candidate rather than per trusted root.
        Path trustedCandidate = trustedPathForCandidate(candidate);

        if (pathStartsWith(trustedCandidate, toTrustedPath(normalizedWorkingDir))) {
            return true;
        }

        for (Path root : trustedRoots) {
            if (pathStartsWith(trustedCandidate, toTrustedPath(root.toAbsolutePath().normalize()))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns {@code true} when {@code rawPath} names a dangerous target
     * that should never be silently modified even inside the working
     * directory.
     *
     * <p>Dangerous targets include:
     * <ul>
     *   <li>Shell config files ({@code .bashrc}, {@code .zshrc}, etc.)</li>
     *   <li>Version-control and IDE metadata directories
     *       ({@code .git}, {@code .ssh}, {@code .vscode}, etc.)</li>
     * </ul>
     *
     * <p>Comparison is case-insensitive on all platforms (Windows
     * filenames are case-insensitive, and even on Unix a user might
     * create {@code .GIT} to try to evade the check).
     */
    public static boolean isDangerousEditTarget(String rawPath, Path workingDir) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }

        Path normalizedWorkingDir = workingDir.toAbsolutePath().normalize();

        Path candidate;
        try {
            Path raw = Path.of(rawPath);
            candidate = raw.isAbsolute()
                    ? raw.normalize()
                    : normalizedWorkingDir.resolve(raw).normalize();
        } catch (RuntimeException e) {
            return false;
        }

        // Check the lexical path AND its symlink-resolved location: a symlink
        // with an innocuous name can point at a dangerous target, and writes
        // follow the link. The resolved scan only runs when resolution actually
        // changed the path.
        if (matchesDangerousName(candidate)) {
            return true;
        }
        Path resolved = trustedPathForCandidate(candidate);
        return !resolved.equals(candidate) && matchesDangerousName(resolved);
    }

    private static boolean matchesDangerousName(Path path) {
        String fileName = path.getFileName() != null
                ? path.getFileName().toString().toLowerCase(Locale.ROOT)
                : "";

        if (DANGEROUS_FILENAMES.contains(fileName)) {
            return true;
        }

        for (Path segment : path) {
            if (DANGEROUS_DIRECTORY_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private static boolean pathStartsWith(Path path, Path prefix) {
        if (path.getNameCount() < prefix.getNameCount()) {
            return false;
        }
        if (isWindows()) {
            for (int i = 0; i < prefix.getNameCount(); i++) {
                if (!path.getName(i).toString().equalsIgnoreCase(prefix.getName(i).toString())) {
                    return false;
                }
            }
            return true;
        }
        return path.startsWith(prefix);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path toTrustedPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
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
}