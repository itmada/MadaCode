package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import madacode.core.session.SessionListener;
import madacode.core.engine.ToolExecutor;

class GrepToolTest {

    private GrepTool tool;
    private ToolUseContext context;
    private Path workingDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new GrepTool();
        workingDir = tempDir;
        context = new ToolUseContext(workingDir, new ConversationSession(workingDir));
    }

    @Test
    void regexPatternMatchesExpectedContent() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve("src/Match.txt"), "prefix foo123 suffix");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo\\d+", null, null, false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/Match.txt"));
    }

    @Test
    void literalMissingPatternDoesNotReportFalsePositive() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve("src/Nope.txt"), "contains nothing relevant");

        ToolResult result = tool.execute(
                new GrepTool.Input("literal-not-present", null, null, false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().isBlank());
    }

    @Test
    void caseInsensitiveMatchingWorks() throws IOException {
        Files.writeString(workingDir.resolve("case.txt"), "HELLO WORLD");

        ToolResult result = tool.execute(
                new GrepTool.Input("hello world", null, null, true, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("case.txt"));
    }

    @Test
    void skipsExcludedDirectories() throws IOException {
        Files.createDirectories(workingDir.resolve(".git"));
        Files.createDirectories(workingDir.resolve("node_modules/pkg"));
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve(".git/ignored.txt"), "foo123");
        Files.writeString(workingDir.resolve("node_modules/pkg/ignored.txt"), "foo123");
        Files.writeString(workingDir.resolve("src/included.txt"), "foo123");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo\\d+", null, null, false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/included.txt"));
        assertFalse(result.output().contains(".git/ignored.txt"));
        assertFalse(result.output().contains("node_modules/pkg/ignored.txt"));
    }

    @Test
    void skipsBinaryFilesSafely() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.write(workingDir.resolve("src/bin.dat"), new byte[] {1, 2, 0, 3, 4});
        Files.writeString(workingDir.resolve("src/text.txt"), "foo123");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo\\d+", null, null, false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/text.txt"));
        assertFalse(result.output().contains("bin.dat"));
    }

    @Test
    void respectsHeadLimitAndAddsTruncationMessage() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve("src/A.txt"), "foo123");
        Files.writeString(workingDir.resolve("src/B.txt"), "foo123");
        Files.writeString(workingDir.resolve("src/C.txt"), "foo123");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo\\d+", null, null, false, "files_with_matches", 0, 2),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("[Results truncated at 2 matches]"));
    }

    @Test
    void supportsContentModeWithContext() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve("src/content.txt"), "line1\nmatch here\nline3\nline4\n");

        ToolResult result = tool.execute(
                new GrepTool.Input("match", null, null, false, "content", 1, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/content.txt:1:line1"));
        assertTrue(result.output().contains("src/content.txt:2:match here"));
        assertTrue(result.output().contains("src/content.txt:3:line3"));
    }

    @Test
    void supportsCountMode() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve("src/count.txt"), "x\nfoo\nfoo\nz\n");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo", null, null, false, "count", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/count.txt:2"));
    }

    @Test
    void reportsInvalidRegexClearly() {
        ToolResult result = tool.execute(
                new GrepTool.Input("foo(", null, null, false, "files_with_matches", 0, 250),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid regex pattern:"));
    }

    @Test
    void globFilterMatchesRelativePath() throws IOException {
        Files.createDirectories(workingDir.resolve("src/main"));
        Files.createDirectories(workingDir.resolve("test"));
        Files.writeString(workingDir.resolve("src/main/App.java"), "foo");
        Files.writeString(workingDir.resolve("test/AppTest.java"), "foo");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo", null, "src/**/*.java", false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/main/App.java"));
        assertFalse(result.output().contains("test/AppTest.java"));
    }

    @Test
    void globFilterStillMatchesFileName() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.createDirectories(workingDir.resolve("src/nested"));
        Files.writeString(workingDir.resolve("src/App.java"), "foo");
        Files.writeString(workingDir.resolve("src/nested/Deep.java"), "foo");

        ToolResult result = tool.execute(
                new GrepTool.Input("foo", "src", "*.java", false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/App.java"));
        assertFalse(result.output().contains("src/nested/Deep.java"));
    }

    @Test
    void skipsSymlinkedDirectoriesWithoutFailing() throws IOException {
        Path realDir = Files.createDirectories(workingDir.resolve("real-dir"));
        Files.writeString(realDir.resolve("nested.txt"), "needle in nested");
        Files.writeString(workingDir.resolve("root.txt"), "needle in root");
        createDirectorySymlink(workingDir.resolve("linked-dir"), realDir);

        ToolResult result = tool.execute(
                new GrepTool.Input("needle", null, null, false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("root.txt"));
        assertTrue(result.output().contains("real-dir/nested.txt"));
        assertFalse(result.output().contains("linked-dir"));
        assertFalse(result.output().contains("Grep failed"));
    }

    @Test
    void resolvesSearchPathOutsideWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path outside = tempDir.resolveSibling("outside-grep");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "needle");

        ToolResult result = tool.execute(
                new GrepTool.Input("needle", "../" + outside.getFileName(), null, false, "files_with_matches", 0, 250),
                context);

        assertTrue(result.success());
        assertTrue(result.output().contains("secret.txt"));
    }

    @Test
    void resolvesSymlinkSearchRootOutsideWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path outside = Files.createTempFile(tempDir.getParent(), "outside-grep-root", ".txt");
        Files.writeString(outside, "needle");
        Path link = workingDir.resolve("linked-root.txt");
        createFileSymlink(link, outside);

        ToolResult result = tool.execute(
                new GrepTool.Input("needle", "linked-root.txt", null, false, "content", 0, 250),
                context);

        assertTrue(result.success(),
                "Grep should resolve symlink paths outside the working directory: " + result.output());
        assertTrue(result.output().contains("needle"),
                "Grep should find content in symlinked outside file: " + result.output());
    }

    @Test
    void emitsProgressOnlyForLargerDirectories() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        for (int i = 0; i < 220; i++) {
            Files.writeString(workingDir.resolve("src/file-" + i + ".txt"), i % 2 == 0 ? "needle" : "other");
        }
        List<String> progress = captureProgress();

        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_grep");
        try {
            ToolResult result = tool.execute(
                    new GrepTool.Input("needle", null, null, false, "files_with_matches", 0, 250),
                    context);
            assertTrue(result.success());
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        assertTrue(progress.stream().anyMatch(p -> p.contains("scanned 200 files")), progress.toString());
    }

    @Test
    void smallDirectoryDoesNotEmitProgress() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        for (int i = 0; i < 20; i++) {
            Files.writeString(workingDir.resolve("src/file-" + i + ".txt"), "needle");
        }
        List<String> progress = captureProgress();

        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_grep");
        try {
            ToolResult result = tool.execute(
                    new GrepTool.Input("needle", null, null, false, "files_with_matches", 0, 250),
                    context);
            assertTrue(result.success());
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        assertTrue(progress.isEmpty(), progress.toString());
    }

    @Test
    void symlinkedWorkingDirectoryUsesTrustedBaseForDisplayAndExcludes(@TempDir Path tempDir) throws IOException {
        Path realWorkingDir = Files.createDirectories(tempDir.resolve("real-work"));
        Path linkedWorkingDir = tempDir.resolve("linked-work");
        createDirectorySymlink(linkedWorkingDir, realWorkingDir);
        Files.createDirectories(realWorkingDir.resolve("src"));
        Files.createDirectories(realWorkingDir.resolve("node_modules/pkg"));
        Files.writeString(realWorkingDir.resolve("src/included.txt"), "needle");
        Files.writeString(realWorkingDir.resolve("node_modules/pkg/ignored.txt"), "needle");

        ToolUseContext symlinkContext = new ToolUseContext(
                linkedWorkingDir,
                new ConversationSession(linkedWorkingDir));
        ToolResult result = tool.execute(
                new GrepTool.Input("needle", null, null, false, "files_with_matches", 0, 250),
                symlinkContext);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/included.txt"));
        assertFalse(result.output().contains("node_modules"));
        assertFalse(result.output().contains("real-work"));
    }

    private void createDirectorySymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            Assumptions.assumeTrue(false, "Symbolic links not available: " + exception.getMessage());
        }
    }

    private void createFileSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            Assumptions.assumeTrue(false, "Symbolic links not available: " + exception.getMessage());
        }
    }

    private List<String> captureProgress() {
        List<String> progress = new ArrayList<>();
        context.session().addListener(new SessionListener() {
            @Override
            public void onToolExecutionMetric(String toolUseId, String metricText) {
                progress.add(metricText);
            }
        });
        return progress;
    }
}
