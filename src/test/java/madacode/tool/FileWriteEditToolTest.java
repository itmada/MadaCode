package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ConversationSession;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileWriteEditToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private FileWriteTool writeTool;
    private FileEditTool editTool;
    private ToolUseContext context;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        writeTool = new FileWriteTool();
        editTool = new FileEditTool();
        ConversationSession session = new ConversationSession(tempDir);
        context = new ToolUseContext(tempDir, session);
    }

    // ---- FileWriteTool tests ----

    @Test
    void writeCreatesNewFile(@TempDir Path tempDir) {
        Path target = tempDir.resolve("hello.txt");
        ObjectNode input = writeInput(target.toString(), "hello world");

        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("created successfully"));
        assertTrue(Files.exists(target));
    }

    @Test
    void writeOverwritesExistingFile(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("existing.txt");
        Files.writeString(target, "old content");
        context.session().readFileState().record(target, false);

        ObjectNode input = writeInput(target.toString(), "new content");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("updated successfully"));
        assertEquals("new content", Files.readString(target));
    }

    @Test
    void writeRejectsRelativePath() {
        ObjectNode input = writeInput("relative/path.txt", "content");

        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("must be absolute"));
    }

    @Test
    void writeRejectsEmptyPath() {
        ObjectNode input = writeInput("", "content");

        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Missing required field"));
    }

    @Test
    void writeCreatesParentDirectories(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("deep/nested/file.txt");
        ObjectNode input = writeInput(target.toString(), "deep content");

        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertTrue(Files.exists(target));
        assertEquals("deep content", Files.readString(target));
    }

    @Test
    void writeDoesNotIncludePatchOrLineChangesForUpdates(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("patch.txt");
        Files.writeString(target, "line1\nline2\nline3\nline4\nline5\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = writeInput(target.toString(), "line1\nline2\nCHANGED\nline4\nline5\n");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        String output = result.output();
        assertTrue(output.contains("updated successfully"));
        assertFalse(output.contains("Line changes:"));
        assertFalse(output.contains("@@"));
        assertFalse(output.contains("+CHANGED"));
    }

    @Test
    void writeHandlesSpecialCharacters(@TempDir Path tempDir) {
        Path target = tempDir.resolve("emoji.txt");
        ObjectNode input = writeInput(target.toString(), "hello emoji 🌍\n中文测试\n");

        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertTrue(Files.exists(target));
    }

    // ---- FileEditTool tests ----

    @Test
    void editSingleReplacement(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("edit.txt");
        Files.writeString(target, "hello world\nfoo bar\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "world", "everyone", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("updated successfully"));
        assertEquals("hello everyone\nfoo bar\n", Files.readString(target));
    }

    @Test
    void editReplaceAll(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("replaceall.txt");
        Files.writeString(target, "foo bar foo baz foo\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "foo", "qux", true);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("All occurrences were replaced"));
        assertEquals("qux bar qux baz qux\n", Files.readString(target));
    }

    @Test
    void editMultipleMatchesWithoutReplaceAll(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("multi.txt");
        Files.writeString(target, "foo bar foo baz foo\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "foo", "qux", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Found "));
        assertTrue(result.output().contains("replace_all"));
        assertTrue(result.output().contains("[errorCode=9]"));
    }

    @Test
    void editOldStringNotFound(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("miss.txt");
        Files.writeString(target, "some content here\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "notfound", "replacement", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("[errorCode=8]"));
    }

    @Test
    void editOldEqualsNew(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("same.txt");
        Files.writeString(target, "unchanged\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "unchanged", "unchanged", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("[errorCode=1]"));
    }

    @Test
    void editFileNotFound(@TempDir Path tempDir) {
        Path target = tempDir.resolve("nonexistent.txt");
        ObjectNode input = editInput(target.toString(), "foo", "bar", false);

        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("[errorCode=3]"));
    }

    @Test
    void editEmptyOldStringCreatesFile(@TempDir Path tempDir) {
        Path target = tempDir.resolve("newfile.txt");
        ObjectNode input = editInput(target.toString(), "", "initial content", false);

        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("created successfully"));
        assertTrue(Files.exists(target));
    }

    @Test
    void editEmptyOldStringOnNonEmptyFile(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("notempty.txt");
        Files.writeString(target, "existing content\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "", "new", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("[errorCode=3]"));
    }

    @Test
    void editEmptyOldStringOnWhitespaceOnlyFileFails(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("whitespace-only.txt");
        Files.writeString(target, "\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "", "new", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("[errorCode=3]"));
        assertEquals("\n", Files.readString(target));
    }

    @Test
    void editEmptyOldStringOnTrulyEmptyFileWritesContent(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("empty.txt");
        Files.writeString(target, "");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "", "new-content", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertEquals("new-content", Files.readString(target));
    }

    @Test
    void editRejectsRelativePath() {
        ObjectNode input = editInput("./relative.txt", "foo", "bar", false);

        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("must be absolute"));
    }

    @Test
    void editReportsLineChangesInResult(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("patch.txt");
        Files.writeString(target, "line1\nline2\nline3\nline4\nline5\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "line3", "LINE_THREE", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("Line changes: +1 -1"));
        assertFalse(result.output().contains("@@"));
        assertFalse(result.output().contains("+LINE_THREE"));
    }

    @Test
    void editPreservesCrLfLineEndings(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("crlf.txt");
        Files.writeString(target, "line1\r\nline2\r\nline3\r\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "line2", "LINE_TWO", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertEquals("line1\r\nLINE_TWO\r\nline3\r\n", Files.readString(target));
    }

    @Test
    void editMatchesOldStringWithCrLfInput(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("crlf-old-string.txt");
        Files.writeString(target, "before\r\nline1\r\nline2\r\nafter\r\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "line1\r\nline2", "replacement", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertEquals("before\r\nreplacement\r\nafter\r\n", Files.readString(target));
    }

    @Test
    void editNormalizesNewStringWithCrLfInputWithoutDoubleCarriageReturns(@TempDir Path tempDir)
            throws IOException {
        Path target = tempDir.resolve("crlf-new-string.txt");
        Files.writeString(target, "before\r\nold\r\nafter\r\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "old", "new1\r\nnew2", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertEquals("before\r\nnew1\r\nnew2\r\nafter\r\n", Files.readString(target));
        assertFalse(Files.readString(target).contains("\r\r\n"));
    }

    @Test
    void editRejectsLineEndingOnlyNoOpAfterNormalization(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("line-ending-noop.txt");
        Files.writeString(target, "a\r\nb\r\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "a\r\nb", "a\nb", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("[errorCode=1]"));
        assertEquals("a\r\nb\r\n", Files.readString(target));
    }

    @Test
    void editPreservesClassicCarriageReturnLineEndings(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("cr-only.txt");
        Files.writeString(target, "line1\rline2\rline3\r");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "line2", "LINE_TWO", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
        assertEquals("line1\rLINE_TWO\rline3\r", Files.readString(target));
    }

    @Test
    void editRejectsDirectoryPath(@TempDir Path tempDir) throws IOException {
        Path dir = tempDir.resolve("edit-dir-target");
        Files.createDirectories(dir);

        ObjectNode input = editInput(dir.toString(), "old", "new", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Path is a directory"));
        assertTrue(result.output().contains("[errorCode=11]"));
    }

    @Test
    void editRejectsBinaryFile(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("binary-edit.bin");
        Files.write(target, new byte[] {65, 0, 66});
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "A", "C", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("binary"));
        assertTrue(result.output().contains("[errorCode=11]"));
    }

    @Test
    void editRejectsFileLargerThanLimit(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("too-large.txt");
        byte[] bytes = new byte[21 * 1024 * 1024];
        Files.write(target, bytes);
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "a", "b", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Maximum size is 20 MiB"));
        assertTrue(result.output().contains("[errorCode=10]"));
    }

    @Test
    void writeOverwritesLargeExistingFileWithoutLineChanges(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("large-existing.txt");
        Files.writeString(target, "x".repeat(3 * 1024 * 1024));
        context.session().readFileState().record(target, false);

        ObjectNode input = writeInput(target.toString(), "new content");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertEquals("new content", Files.readString(target));
        assertFalse(result.output().contains("Line changes:"));
    }

    @Test
    void writeOverwritesBinaryExistingFileWithoutLineChanges(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("binary-existing.bin");
        Files.write(target, new byte[] {1, 2, 0, 3, 4});
        context.session().readFileState().record(target, false);

        ObjectNode input = writeInput(target.toString(), "text now");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertEquals("text now", Files.readString(target));
        assertFalse(result.output().contains("Line changes:"));
    }

    @Test
    void writeRejectsDirectoryPath(@TempDir Path tempDir) throws IOException {
        Path dir = tempDir.resolve("dir-target");
        Files.createDirectories(dir);

        ObjectNode input = writeInput(dir.toString(), "content");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Path is a directory"));
    }

    @Test
    void writeDoesNotReportInsertedLines(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("insert-delete.txt");
        Files.writeString(target, "a\nb\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = writeInput(target.toString(), "a\nb\nc\n");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertFalse(result.output().contains("Line changes:"));
        assertFalse(result.output().contains("@@"));
    }

    @Test
    void writeDoesNotReportDeletedLines(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("delete.txt");
        Files.writeString(target, "a\nb\nc\n");
        context.session().readFileState().record(target, false);

        ObjectNode input = writeInput(target.toString(), "a\nc\n");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertTrue(result.success());
        assertFalse(result.output().contains("Line changes:"));
        assertFalse(result.output().contains("@@"));
    }

    // ---- ReadFileState enforcement tests ----

    @Test
    void editRejectsWithoutPriorRead(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("no-read.txt");
        Files.writeString(target, "content");

        ObjectNode input = editInput(target.toString(), "content", "changed", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("not been read yet"));
        assertTrue(result.output().contains("[errorCode=12]"));
    }

    @Test
    void editRejectsAfterExternalModification(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path target = tempDir.resolve("modified.txt");
        Files.writeString(target, "original");
        context.session().readFileState().record(target, false);

        Thread.sleep(50);
        Files.writeString(target, "externally changed");

        ObjectNode input = editInput(target.toString(), "original", "new", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("modified since"));
        assertTrue(result.output().contains("[errorCode=12]"));
    }

    @Test
    void editRejectsPartialView(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("partial.txt");
        Files.writeString(target, "full content here");
        context.session().readFileState().record(target, true);

        ObjectNode input = editInput(target.toString(), "content", "text", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("partially read"));
        assertTrue(result.output().contains("[errorCode=12]"));
    }

    @Test
    void editSucceedsAfterReadThenWrite(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("read-edit-edit.txt");
        Files.writeString(target, "aaa bbb ccc");
        context.session().readFileState().record(target, false);

        ObjectNode input1 = editInput(target.toString(), "bbb", "BBB", false);
        ToolResult r1 = ToolTestSupport.invoke(editTool, input1, context);
        assertTrue(r1.success());

        ObjectNode input2 = editInput(target.toString(), "BBB", "XXX", false);
        ToolResult r2 = ToolTestSupport.invoke(editTool, input2, context);
        assertTrue(r2.success());
        assertEquals("aaa XXX ccc", Files.readString(target));
    }

    @Test
    void writeRejectsExistingFileWithoutPriorRead(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("no-read-write.txt");
        Files.writeString(target, "original");

        ObjectNode input = writeInput(target.toString(), "overwritten");
        ToolResult result = ToolTestSupport.invoke(writeTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("not been read yet"));
    }

    @Test
    void editRejectsIpynbFile(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("notebook.ipynb");
        Files.writeString(target, "{\"cells\":[]}");

        ObjectNode input = editInput(target.toString(), "{", "[", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Jupyter"));
        assertTrue(result.output().contains("[errorCode=5]"));
    }

    @Test
    void editMatchesCurlyQuotes(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("curly.txt");
        Files.writeString(target, "He said “hello” to everyone");
        context.session().readFileState().record(target, false);

        ObjectNode input = editInput(target.toString(), "He said \"hello\" to everyone", "She said \"hi\" to everyone", false);
        ToolResult result = ToolTestSupport.invoke(editTool, input, context);

        assertTrue(result.success());
    }

    // ---- isFileEdit self-classification ----

    @Test
    void writeToolDeclaresItselfAsFileEdit() {
        assertTrue(writeTool.isFileEdit(),
                "FileWriteTool must declare isFileEdit=true so AcceptEditsPermissionRule can auto-allow it");
    }

    @Test
    void editToolDeclaresItselfAsFileEdit() {
        assertTrue(editTool.isFileEdit(),
                "FileEditTool must declare isFileEdit=true so AcceptEditsPermissionRule can auto-allow it");
    }

    @Test
    void fileReadIsNotAFileEdit() {
        // Regression guard: only mutating file tools should be classified as edits.
        // FileReadTool is read-only and must NOT be auto-allowed in ACCEPT_EDITS
        // mode by isFileEdit; it's handled by isReadOnly upstream of this rule.
        assertFalse(new FileReadTool().isFileEdit(),
                "FileReadTool must not declare isFileEdit=true");
    }

    // ---- helpers ----

    private ObjectNode writeInput(String path, String content) {
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return input;
    }

    private ObjectNode editInput(String path, String oldString, String newString, boolean replaceAll) {
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldString);
        input.put("new_string", newString);
        input.put("replace_all", replaceAll);
        return input;
    }
}
