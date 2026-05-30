package madacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.engine.ToolUseContext;
import madacode.tool.FileEditTool;
import madacode.tool.FileReadTool;
import madacode.tool.FileWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AcceptEditsPermissionRuleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workingDir;

    @Test
    void nonFileEditToolsAreNotHandled() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileReadTool tool = new FileReadTool();

        Optional<PermissionDecision> result = rule.evaluate(
                tool, mapper.createObjectNode(), context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isEmpty());
    }

    @Test
    void fileEditInDefaultModeFallsThrough() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = writeInput(workingDir.resolve("test.txt").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.DEFAULT));

        assertTrue(result.isEmpty());
    }

    @Test
    void fileEditInAcceptEditsModeInsideWorkingDirIsAllowed() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = writeInput(workingDir.resolve("test.txt").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isPresent());
        assertTrue(result.get().isAllowed());
        assertEquals(AcceptEditsPermissionRule.SOURCE, result.get().source());
    }

    @Test
    void fileEditInAcceptEditsOutsideWorkingDirFallsThrough() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = writeInput("/tmp/outside.txt");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isEmpty());
    }

    @Test
    void fileEditInAcceptEditsModeDangerousTargetFallsThrough() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileEditTool tool = new FileEditTool();
        ObjectNode input = editInput(workingDir.resolve(".bashrc").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isEmpty());
    }

    @Test
    void fileEditInAcceptEditsModeGitHooksFallsThrough() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = writeInput(workingDir.resolve(".git").resolve("hooks").resolve("pre-commit").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isEmpty());
    }

    @Test
    void fileEditInAcceptEditsModeVscodeSettingsFallsThrough() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileEditTool tool = new FileEditTool();
        ObjectNode input = editInput(workingDir.resolve(".vscode").resolve("settings.json").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isEmpty());
    }

    @Test
    void relativePathOutsideWorkingDirFallsThrough() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = writeInput("../outside.txt");

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.ACCEPT_EDITS));

        assertTrue(result.isEmpty());
    }

    @Test
    void bypassModeDoesNotAutoAllowFileEditEvenWithAcceptEditsRule() {
        AcceptEditsPermissionRule rule = new AcceptEditsPermissionRule();
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = writeInput(workingDir.resolve("test.txt").toString());

        Optional<PermissionDecision> result = rule.evaluate(
                tool, input, context(PermissionMode.BYPASS));

        assertTrue(result.isEmpty(),
                "AcceptEditsPermissionRule should only match ACCEPT_EDITS mode, not BYPASS");
    }

    private ToolUseContext context(PermissionMode mode) {
        ConversationSession session = new ConversationSession(workingDir);
        session.setPermissionMode(mode);
        return new ToolUseContext(workingDir, session);
    }

    private ObjectNode writeInput(String path) {
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", path);
        input.put("content", "hello");
        return input;
    }

    private ObjectNode editInput(String path) {
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", "old");
        input.put("new_string", "new");
        return input;
    }
}