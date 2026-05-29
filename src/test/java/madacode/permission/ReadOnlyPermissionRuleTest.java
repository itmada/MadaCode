package madacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ConversationSession;
import madacode.core.ToolUseContext;
import madacode.tool.FileEditTool;
import madacode.tool.FileReadTool;
import madacode.tool.FileWriteTool;
import madacode.tool.GrepTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyPermissionRuleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workingDir;

    @Test
    void nonReadOnlyToolsAreNotHandled() {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/test.txt");
        input.put("content", "hello");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isEmpty());
    }

    @Test
    void readOnlyToolWithNoTargetsIsAllowed() {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule();
        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "hello");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isPresent());
        assertTrue(result.get().isAllowed());
        assertEquals(ReadOnlyPermissionRule.SOURCE, result.get().source());
    }

    @Test
    void readOnlyToolWithTargetInsideWorkingDirIsAllowed() {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule();
        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "src/Main.java");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isPresent());
        assertTrue(result.get().isAllowed());
    }

    @Test
    void readOnlyToolWithTargetOutsideWorkingDirFallsThrough() {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule();
        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "../outside.txt");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isEmpty());
    }

    @Test
    void readOnlyToolWithTargetInTrustedRootsIsAllowed(@TempDir Path blobsDir) {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule(List.of(blobsDir));
        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("path", blobsDir.resolve("data.bin").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isPresent());
        assertTrue(result.get().isAllowed());
    }

    @Test
    void readOnlyToolWithAbsolutePathOutsideWorkingDirFallsThrough() {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule();
        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "/etc/passwd");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyPathIsAllowed() {
        ReadOnlyPermissionRule rule = new ReadOnlyPermissionRule();
        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isPresent());
        assertTrue(result.get().isAllowed());
    }

    private ToolUseContext context(PermissionMode mode) {
        ConversationSession session = new ConversationSession(workingDir);
        session.setPermissionMode(mode);
        return new ToolUseContext(workingDir, session);
    }
}