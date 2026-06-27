package madacode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    private static final int MAX_SYMLINK_DEPTH = 40;

    private static final Set<String> DANGEROUS_FILENAMES = Set.of(
            ".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile",
            ".gitconfig", ".gitmodules", ".mcp.json", ".claude.json",
            ".ripgreprc"
    );

    private static final Set<String> DANGEROUS_DIRECTORY_SEGMENTS = Set.of(
            ".git", ".ssh", ".vscode", ".idea", ".claude"
    );

    private static final Set<String> PROTECTED_LONG_RUNNING_STATE_FILENAMES = Set.of(
            "task.json", "feature_list.json", "known_issues.json", "progress.txt",
            "events.jsonl", "checkpoint.json", "init.sh", ".execution.lock", ".state.lock"
    );

    /**
     * Returns {@code true} when every filesystem location represented by
     * {@code rawPath} is inside {@code workingDir} or one of the
     * {@code trustedRoots}.
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
        if (rawPath == null || rawPath.isBlank()) {
            return true;
        }

        List<Path> candidates = permissionPaths(rawPath, workingDir);
        if (candidates.isEmpty()) {
            return false;
        }

        List<Path> roots = rootPaths(workingDir, trustedRoots);

        // The lexical path and every resolved/symlink-derived path must be in
        // scope. This keeps symlinked working directories usable while blocking
        // links that escape to another location, including dangling links whose
        // target would be created by a write.
        for (Path candidate : candidates) {
            boolean insideAnyRoot = false;
            for (Path root : roots) {
                if (pathStartsWith(candidate, root)) {
                    insideAnyRoot = true;
                    break;
                }
            }
            if (!insideAnyRoot) {
                return false;
            }
        }

        return true;
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

        // Check the lexical path and every symlink-derived destination. A
        // dangling symlink with an innocuous name can point at a dangerous
        // target that a write would create.
        return permissionPaths(rawPath, workingDir).stream()
                .anyMatch(FilesystemScope::matchesDangerousName);
    }

    /**
     * Returns {@code true} when {@code rawPath} names a long-running task
     * state file that must be updated through the dedicated task-store tool
     * rather than generic write/edit tools.
     */
    public static boolean isProtectedLongRunningTaskStateTarget(String rawPath, Path workingDir) {
        return isProtectedLongRunningTaskStateTarget(rawPath, workingDir, null);
    }

    /**
     * Returns {@code true} when {@code rawPath} names a long-running task
     * state file that must be updated through the dedicated task-store tools.
     *
     * <p>When {@code activeTaskDirectory} is non-null, only files inside that
     * task directory match. This lets the permission policy protect the active
     * runtime boundary without blocking unrelated task cleanup.
     */
    public static boolean isProtectedLongRunningTaskStateTarget(
            String rawPath,
            Path workingDir,
            Path activeTaskDirectory) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }

        Path normalizedWorkingDir = workingDir.toAbsolutePath().normalize();
        Path taskRoot = normalizedWorkingDir.resolve(".mada/long-running").normalize();
        Path trustedTaskRoot = toTrustedPath(taskRoot);
        Path normalizedActiveTaskDirectory = activeTaskDirectory == null
                ? null
                : activeTaskDirectory.toAbsolutePath().normalize();
        Path trustedActiveTaskDirectory = normalizedActiveTaskDirectory == null
                ? null
                : toTrustedPath(normalizedActiveTaskDirectory);
        for (Path candidate : permissionPaths(rawPath, workingDir)) {
            String fileName = candidate.getFileName() == null
                    ? ""
                    : candidate.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!PROTECTED_LONG_RUNNING_STATE_FILENAMES.contains(fileName)) {
                continue;
            }
            boolean insideTaskRoot = pathStartsWith(candidate, taskRoot)
                    || pathStartsWith(candidate, trustedTaskRoot);
            if (!insideTaskRoot) {
                continue;
            }
            if (normalizedActiveTaskDirectory == null) {
                return true;
            }
            if (pathStartsWith(candidate, normalizedActiveTaskDirectory)
                    || pathStartsWith(candidate, trustedActiveTaskDirectory)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when a bash command touches the long-running task
     * store area.
     *
     * <p>Bash does not provide structured path arguments to the permission gate.
     * The path-aware active-task check is therefore reserved for file tools; shell
     * commands that mention the task-store root are denied and should use the
     * dedicated long-running environment tools instead.
     */
    public static boolean isProtectedLongRunningTaskStateShellAccess(String command, Path workingDir) {
        return isProtectedLongRunningTaskStateShellAccess(command, workingDir, null);
    }

    public static boolean isProtectedLongRunningTaskStateShellAccess(
            String command,
            Path workingDir,
            Path activeTaskDirectory) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = normalizeShellCommand(command);
        Path workingRoot = workingDir.toAbsolutePath().normalize();
        Path taskStoreRoot = workingRoot.resolve(".mada/long-running").normalize();
        return referencesLongRunningTaskRoot(normalized, workingRoot, taskStoreRoot);
    }

    private static String normalizeShellCommand(String command) {
        return command.replace('\\', '/').strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeShellPath(Path path) {
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean referencesLongRunningTaskRoot(String command, Path workingRoot, Path taskStoreRoot) {
        String absoluteRoot = normalizeShellPath(taskStoreRoot);
        String relativeRoot = normalizeShellPath(workingRoot.relativize(taskStoreRoot));
        String dottedRelativeRoot = relativeRoot.replace(".mada/long-running", ".mada/./long-running");
        return command.contains(absoluteRoot)
                || command.contains(relativeRoot)
                || command.contains(dottedRelativeRoot)
                || command.contains(".mada")
                        && command.contains("long-running");
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
        path = normalizeSystemAlias(path);
        prefix = normalizeSystemAlias(prefix);
        if (path.getNameCount() < prefix.getNameCount()) {
            return false;
        }
        if (isWindows()) {
            String pathRoot = path.getRoot() != null ? path.getRoot().toString() : "";
            String prefixRoot = prefix.getRoot() != null ? prefix.getRoot().toString() : "";
            if (!pathRoot.equalsIgnoreCase(prefixRoot)) {
                return false;
            }
            for (int i = 0; i < prefix.getNameCount(); i++) {
                if (!path.getName(i).toString().equalsIgnoreCase(prefix.getName(i).toString())) {
                    return false;
                }
            }
            return true;
        }
        return path.startsWith(prefix);
    }

    private static Path normalizeSystemAlias(Path path) {
        if (!isMacOs()) {
            return path;
        }
        String value = path.toString();
        if (value.startsWith("/private/var/")) {
            return Path.of("/var" + value.substring("/private/var".length()));
        }
        if (value.equals("/private/var")) {
            return Path.of("/var");
        }
        if (value.startsWith("/private/tmp/")) {
            return Path.of("/tmp" + value.substring("/private/tmp".length()));
        }
        if (value.equals("/private/tmp")) {
            return Path.of("/tmp");
        }
        return path;
    }

    private static boolean isMacOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") || osName.contains("darwin");
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

    private static List<Path> rootPaths(Path workingDir, List<Path> trustedRoots) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        addRootPath(paths, workingDir);
        for (Path root : trustedRoots) {
            addRootPath(paths, root);
        }
        return List.copyOf(paths);
    }

    private static void addRootPath(Set<Path> paths, Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        paths.add(normalized);
        paths.add(toTrustedPath(normalized));
    }

    private static List<Path> permissionPaths(String rawPath, Path workingDir) {
        Path normalizedWorkingDir = workingDir.toAbsolutePath().normalize();

        Path candidate;
        try {
            Path raw = Path.of(rawPath);
            candidate = raw.isAbsolute()
                    ? raw.normalize()
                    : normalizedWorkingDir.resolve(raw).normalize();
        } catch (RuntimeException e) {
            return List.of();
        }

        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(candidate.toAbsolutePath().normalize());

        Path resolved = resolvedPathForCandidate(candidate);
        if (!resolved.equals(candidate)) {
            paths.add(resolved);
        }

        return List.copyOf(paths);
    }

    private static Path resolvedPathForCandidate(Path candidate) {
        return resolvedPathForCandidate(candidate, 0);
    }

    private static Path resolvedPathForCandidate(Path candidate, int depth) {
        if (depth >= MAX_SYMLINK_DEPTH) {
            return candidate.toAbsolutePath().normalize();
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return resolveExistingPath(candidate, depth);
        }

        Path current = candidate;
        List<Path> missingTail = new ArrayList<>();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Path resolvedPrefix = resolveExistingPath(current, depth);
                return appendTail(resolvedPrefix, missingTail);
            }
            Path fileName = current.getFileName();
            if (fileName != null) {
                missingTail.addFirst(fileName);
            }
            current = current.getParent();
        }

        return candidate.toAbsolutePath().normalize();
    }

    private static Path resolveExistingPath(Path path) {
        return resolveExistingPath(path, 0);
    }

    private static Path resolveExistingPath(Path path, int depth) {
        if (depth >= MAX_SYMLINK_DEPTH) {
            return path.toAbsolutePath().normalize();
        }
        try {
            return path.toRealPath();
        } catch (IOException firstFailure) {
            if (Files.isSymbolicLink(path)) {
                try {
                    Path target = Files.readSymbolicLink(path);
                    Path resolved = target.isAbsolute()
                            ? target
                            : path.getParent().resolve(target);
                    return resolvedPathForCandidate(resolved.toAbsolutePath().normalize(), depth + 1);
                } catch (IOException ignored) {
                    return path.toAbsolutePath().normalize();
                }
            }
            return path.toAbsolutePath().normalize();
        }
    }

    private static Path appendTail(Path prefix, List<Path> tail) {
        Path resolved = prefix;
        for (Path segment : tail) {
            resolved = resolved.resolve(segment);
        }
        return resolved.toAbsolutePath().normalize();
    }
}
