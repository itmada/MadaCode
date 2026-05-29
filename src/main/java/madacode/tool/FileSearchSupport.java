package madacode.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class FileSearchSupport {

    static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git",
            ".svn",
            ".hg",
            "node_modules",
            "target",
            "build",
            "dist",
            ".idea",
            ".gradle");

    static final int MAX_RESULTS = 500;
    private static final int BINARY_PROBE_BYTES = 8192;

    private FileSearchSupport() {
    }

    @FunctionalInterface
    interface PathVisitor {
        boolean visit(Path path) throws IOException;
    }

    static boolean isExcluded(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        Path relative = normalizedRoot.relativize(normalizedPath);
        for (Path part : relative) {
            if (DEFAULT_EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    static boolean isLikelyBinary(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        byte[] buffer = new byte[BINARY_PROBE_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(buffer);
            if (read <= 0) {
                return false;
            }
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    static Stream<Path> safeWalk(Path root) throws IOException {
        List<Path> visited = new ArrayList<>();
        walkPaths(root, path -> {
            visited.add(path);
            return true;
        }, true);
        return visited.stream();
    }

    static void walkFiles(Path root, PathVisitor visitor) throws IOException {
        walkPaths(root, visitor, false);
    }

    private static void walkPaths(Path root, PathVisitor visitor, boolean includeDirectories)
            throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(normalizedRoot) && isExcluded(normalizedRoot, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (includeDirectories && !visitor.visit(dir)) {
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!isExcluded(normalizedRoot, file)) {
                    return visitor.visit(file)
                            ? FileVisitResult.CONTINUE
                            : FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String relativizeOrAbsolute(Path cwd, Path path) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        try {
            return normalizedCwd.relativize(normalizedPath).toString();
        } catch (IllegalArgumentException ignored) {
            return normalizedPath.toString();
        }
    }
}
