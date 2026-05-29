package madacode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileSearchSupportTest {

    @Test
    void walkFilesStopsWhenVisitorReturnsFalse(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("one.txt"), "one");
        Files.writeString(tempDir.resolve("two.txt"), "two");
        Files.writeString(tempDir.resolve("three.txt"), "three");

        AtomicInteger visited = new AtomicInteger();
        FileSearchSupport.walkFiles(tempDir, file -> {
            visited.incrementAndGet();
            return false;
        });

        assertEquals(1, visited.get());
    }

    @Test
    void walkFilesSkipsExcludedSubtrees(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve(".git/objects"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".git/objects/ignored.txt"), "ignored");
        Files.writeString(tempDir.resolve("node_modules/pkg/ignored.js"), "ignored");
        Files.writeString(tempDir.resolve("src/kept.java"), "kept");

        StringBuilder seen = new StringBuilder();
        FileSearchSupport.walkFiles(tempDir, file -> {
            seen.append(tempDir.relativize(file)).append('\n');
            return true;
        });

        assertFalse(seen.toString().contains(".git"));
        assertFalse(seen.toString().contains("node_modules"));
        assertEquals("src/kept.java\n", seen.toString());
    }

    @Test
    void walkFilesSkipsSymlinkToDirectory(@TempDir Path tempDir) throws IOException {
        Path realDir = Files.createDirectories(tempDir.resolve("real-dir"));
        Files.writeString(realDir.resolve("nested.txt"), "nested");
        Files.writeString(tempDir.resolve("root.txt"), "root");
        createDirectorySymlink(tempDir.resolve("linked-dir"), realDir);

        List<String> seen = new ArrayList<>();
        FileSearchSupport.walkFiles(tempDir, file -> {
            seen.add(tempDir.relativize(file).toString());
            return true;
        });

        assertEquals(List.of("real-dir/nested.txt", "root.txt"), seen);
    }

    private void createDirectorySymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            Assumptions.assumeTrue(false, "Symbolic links not available: " + exception.getMessage());
        }
    }
}
