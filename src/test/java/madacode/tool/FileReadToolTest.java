package madacode.tool;

import madacode.core.ConversationSession;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FileReadToolTest {

    private FileReadTool tool;
    private ToolUseContext context;
    private Path workingDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new FileReadTool();
        workingDir = tempDir;
        context = new ToolUseContext(workingDir, new ConversationSession(workingDir));
    }

    @Test
    void defaultReadsFirst2000LinesWithTruncationNotice() throws IOException {
        Path file = workingDir.resolve("many-lines.txt");
        Files.write(file, lines(2_100), StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new FileReadTool.Input("many-lines.txt", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("1\tline-1"));
        assertTrue(result.output().contains("2000\tline-2000"));
        assertFalse(result.output().contains("2001\tline-2001"));
        assertTrue(result.output().contains("[File truncated: showing lines 1-2000. Use offset and limit to read more.]"));
    }

    @Test
    void readsRequestedRangeWithLineNumbers() throws IOException {
        Path file = workingDir.resolve("range.txt");
        Files.write(file, lines(50), StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new FileReadTool.Input("range.txt", 10, 5), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("10\tline-10"));
        assertTrue(result.output().contains("14\tline-14"));
        assertFalse(result.output().contains("15\tline-15"));
    }

    @Test
    void rangeReadOnLargeFileOnlyReturnsRequestedLines() throws IOException {
        Path file = workingDir.resolve("large-range.txt");
        Files.write(file, lines(50_000), StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new FileReadTool.Input("large-range.txt", 49_000, 3), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("49000\tline-49000"));
        assertTrue(result.output().contains("49002\tline-49002"));
        assertFalse(result.output().contains("49003\tline-49003"));
    }

    @Test
    void rejectsBinaryFiles() throws IOException {
        Path file = workingDir.resolve("binary.bin");
        Files.write(file, new byte[] {1, 2, 0, 4});

        ToolResult result = tool.execute(new FileReadTool.Input("binary.bin", null, null), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Cannot read binary file as text"));
    }

    @Test
    void rejectsDirectoryPath() throws IOException {
        Path dir = workingDir.resolve("folder");
        Files.createDirectories(dir);

        ToolResult result = tool.execute(new FileReadTool.Input("folder", null, null), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Path is a directory"));
    }

    @Test
    void flagsLargeFileWithoutRangeAsTruncated() throws IOException {
        Path file = workingDir.resolve("big-bytes.txt");
        String content = "x".repeat(1_100_000);
        Files.writeString(file, content);

        ToolResult result = tool.execute(new FileReadTool.Input("big-bytes.txt", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("[File truncated: showing lines 1-1. Use offset and limit to read more.]"));
    }

    @Test
    void largeSingleLineIsTruncated() throws IOException {
        Path file = workingDir.resolve("huge-single-line.txt");
        String content = "x".repeat(500_000);
        Files.writeString(file, content);

        ToolResult result = tool.execute(new FileReadTool.Input("huge-single-line.txt", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().length() < 120_000);
        assertTrue(result.output().contains("[line truncated]") || result.output().contains("[Output truncated"));
        assertNotEquals(content, result.output());
    }

    @Test
    void longLineInsideRangeIsTruncated() throws IOException {
        Path file = workingDir.resolve("huge-range-line.txt");
        String content = "y".repeat(500_000);
        Files.writeString(file, content);

        ToolResult result = tool.execute(new FileReadTool.Input("huge-range-line.txt", 1, 1), context);

        assertTrue(result.success());
        assertTrue(result.output().length() < 120_000);
        assertTrue(result.output().contains("[line truncated]") || result.output().contains("[Output truncated"));
        assertNotEquals(content, result.output());
    }

    @Test
    void resolvesPathOutsideWorkingDirectoryViaParentTraversal(@TempDir Path tempDir) throws IOException {
        Path outside = tempDir.resolveSibling("outside-read.txt");
        Files.writeString(outside, "secret");

        ToolResult result = tool.execute(
                new FileReadTool.Input("../" + outside.getFileName(), null, null),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("secret"));
    }

    @Test
    void resolvesSymlinkOutsideWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path outside = tempDir.resolveSibling("outside-secret.txt");
        Files.writeString(outside, "secret");
        Path symlink = workingDir.resolve("linked-secret.txt");
        createSymlink(symlink, outside);

        ToolResult result = tool.execute(new FileReadTool.Input("linked-secret.txt", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("secret"));
    }

    private void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            Assumptions.assumeTrue(false, "Symbolic links not available: " + exception.getMessage());
        }
    }

    private List<String> lines(int count) {
        List<String> lines = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            lines.add("line-" + i);
        }
        return lines;
    }
}
