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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobToolTest {

    private GlobTool tool;
    private ToolUseContext context;
    private Path workingDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new GlobTool();
        workingDir = tempDir;
        context = new ToolUseContext(workingDir, new ConversationSession(workingDir));
    }

    @Test
    void pathLimitsSearchScope() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.createDirectories(workingDir.resolve("other"));
        Files.writeString(workingDir.resolve("src/InScope.java"), "class InScope {}");
        Files.writeString(workingDir.resolve("other/OutOfScope.java"), "class OutOfScope {}");

        ToolResult result = tool.execute(new GlobTool.Input("*.java", "src", null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/InScope.java"));
        assertFalse(result.output().contains("other/OutOfScope.java"));
    }

    @Test
    void excludesGitAndNodeModulesByDefault() throws IOException {
        Files.createDirectories(workingDir.resolve(".git"));
        Files.createDirectories(workingDir.resolve("node_modules/pkg"));
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve(".git/ignored.java"), "ignored");
        Files.writeString(workingDir.resolve("node_modules/pkg/ignored.js"), "ignored");
        Files.writeString(workingDir.resolve("src/kept.java"), "kept");

        ToolResult result = tool.execute(new GlobTool.Input("**/*.*", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/kept.java"));
        assertFalse(result.output().contains(".git/ignored.java"));
        assertFalse(result.output().contains("node_modules/pkg/ignored.js"));
    }

    @Test
    void appliesLimitAndShowsTruncationNotice() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        Files.writeString(workingDir.resolve("src/A.java"), "A");
        Files.writeString(workingDir.resolve("src/B.java"), "B");
        Files.writeString(workingDir.resolve("src/C.java"), "C");

        ToolResult result = tool.execute(new GlobTool.Input("src/*.java", null, 2), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("[Results truncated at 2 files]"));
    }

    @Test
    void returnsClearErrorForMissingPath() {
        ToolResult result = tool.execute(new GlobTool.Input("**/*.java", "missing", null), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Path does not exist"));
    }

    @Test
    void matchesNestedJavaFilesWithSrcGlob() throws IOException {
        Files.createDirectories(workingDir.resolve("src/main/java/example"));
        Files.createDirectories(workingDir.resolve("test"));
        Files.writeString(workingDir.resolve("src/main/java/example/App.java"), "class App {}");
        Files.writeString(workingDir.resolve("test/AppTest.java"), "class AppTest {}");

        ToolResult result = tool.execute(new GlobTool.Input("src/**/*.java", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/main/java/example/App.java"));
        assertFalse(result.output().contains("test/AppTest.java"));
    }

    @Test
    void stopsCollectingAfterLimitPlusOne() throws IOException {
        Files.createDirectories(workingDir.resolve("src"));
        for (int i = 0; i < 200; i++) {
            Files.writeString(workingDir.resolve("src/File" + i + ".java"), "class File" + i + " {}");
        }

        ToolResult result = tool.execute(new GlobTool.Input("src/*.java", null, 10), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("[Results truncated at 10 files]"));
        long listedFiles = Arrays.stream(result.output().split("\\R"))
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("[Results truncated"))
                .count();
        assertEquals(10, listedFiles);
    }

    @Test
    void skipsSymlinkedDirectoriesFromMatches() throws IOException {
        Path realDir = Files.createDirectories(workingDir.resolve("real-dir"));
        Files.writeString(realDir.resolve("nested.java"), "class Nested {}");
        Files.writeString(workingDir.resolve("root.java"), "class Root {}");
        createDirectorySymlink(workingDir.resolve("linked-dir"), realDir);

        ToolResult result = tool.execute(new GlobTool.Input("**/*.java", null, null), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("real-dir/nested.java"));
        assertFalse(result.output().contains("linked-dir"));
    }

    @Test
    void resolvesGlobPathOutsideWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path outside = tempDir.resolveSibling("outside-glob");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}");

        ToolResult result = tool.execute(
                new GlobTool.Input("**/*.java", "../" + outside.getFileName(), null),
                context);

        assertTrue(result.success(),
                "Glob should resolve paths outside the working directory: " + result.output());
    }

    @Test
    void resolvesMissingPathUnderSymlinkOutsideWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path outside = Files.createDirectories(tempDir.resolveSibling("outside-glob-link"));
        createDirectorySymlink(workingDir.resolve("linked-outside"), outside);

        ToolResult result = tool.execute(
                new GlobTool.Input("**/*.java", "linked-outside/missing", null),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Path does not exist"));
    }

    @Test
    void displaysMatchesRelativeToSymlinkedWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path realWorkingDir = Files.createDirectories(tempDir.resolve("real-work"));
        Path linkedWorkingDir = tempDir.resolve("linked-work");
        createDirectorySymlink(linkedWorkingDir, realWorkingDir);
        Files.createDirectories(realWorkingDir.resolve("src"));
        Files.writeString(realWorkingDir.resolve("src/App.java"), "class App {}");

        ToolUseContext symlinkContext = new ToolUseContext(
                linkedWorkingDir,
                new ConversationSession(linkedWorkingDir));
        ToolResult result = tool.execute(new GlobTool.Input("**/*.java", null, null), symlinkContext);

        assertTrue(result.success());
        assertTrue(result.output().contains("src/App.java"));
        assertFalse(result.output().contains("real-work"));
    }

    private void createDirectorySymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            Assumptions.assumeTrue(false, "Symbolic links not available: " + exception.getMessage());
        }
    }
}
