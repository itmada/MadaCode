package madacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.BashTool;
import madacode.tool.FileReadTool;
import madacode.tool.FileWriteTool;
import madacode.tool.LongRunTaskUpdateTool;
import madacode.tool.Tool;
import madacode.tool.WorkerReportTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningWorkspacePermissionRuleTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deniesRelativeBashEscapesFromWorker() {
        PermissionDecision decision = decision("cd .. && rm -rf sibling");

        assertFalse(decision.isAllowed());
    }

    @Test
    void deniesGitCOutsideWorkspace() {
        PermissionDecision decision = decision("git -C .. status");

        assertFalse(decision.isAllowed());
    }

    @Test
    void allowsWorkspaceLocalBash() {
        PermissionDecision decision = decision("mkdir -p build && git status");

        assertTrue(decision.isAllowed());
    }

    @Test
    void allowsWorkspaceFileWriteWithoutPrompting() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.DENY);
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.resolve("src/App.java").toString());
        input.put("content", "class App {}\n");

        PermissionDecision decision = gate(prompt)
                .check(new FileWriteTool(), input, new ToolUseContext(tempDir, workerSession()));

        assertTrue(decision.isAllowed());
        assertEquals(0, prompt.calls());
    }

    @Test
    void deniesWorkspaceOutsideFileWriteWithoutPrompting() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.getParent().resolve("outside.txt").toString());
        input.put("content", "nope");

        PermissionDecision decision = gate(prompt)
                .check(new FileWriteTool(), input, new ToolUseContext(tempDir, workerSession()));

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("inside the workspace"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void allowsReadOnlyOutsideWorkspaceWithoutPrompting() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.DENY);
        ObjectNode input = mapper.createObjectNode();
        input.put("path", tempDir.getParent().resolve("outside.txt").toString());

        PermissionDecision decision = gate(prompt)
                .check(new FileReadTool(), input, new ToolUseContext(tempDir, workerSession()));

        assertTrue(decision.isAllowed());
        assertEquals(0, prompt.calls());
    }

    @Test
    void allowsWorkerTaskStoreToolsWithoutPrompting() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.DENY);
        ObjectNode input = mapper.createObjectNode();
        input.put("task_id", "task-001");
        input.put("status", "progress_made");
        input.put("summary", "updated");

        PermissionDecision reportDecision = gate(prompt)
                .check(new WorkerReportTool(), input, new ToolUseContext(tempDir, workerSession()));
        PermissionDecision updateDecision = gate(prompt)
                .check(new LongRunTaskUpdateTool(), input, new ToolUseContext(tempDir, workerSession()));

        assertTrue(reportDecision.isAllowed());
        assertTrue(updateDecision.isAllowed());
        assertEquals(0, prompt.calls());
    }

    @Test
    void deniesOtherMutatingToolsWithoutPrompting() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        ObjectNode input = mapper.createObjectNode();

        PermissionDecision decision = gate(prompt)
                .check(new MutatingTool(), input, new ToolUseContext(tempDir, workerSession()));

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("cannot request interactive approval"));
        assertEquals(0, prompt.calls());
    }

    private PermissionDecision decision(String command) {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return gate(new RecordingPrompt(ApprovalResponse.DENY))
                .check(new BashTool(), input, new ToolUseContext(tempDir, workerSession()));
    }

    private DefaultPermissionGate gate(RecordingPrompt prompt) {
        return new DefaultPermissionGate(prompt);
    }

    private ConversationSession workerSession() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningWorkerSession(true);
        session.setPermissionMode(PermissionMode.LONG_RUNNING_WORKSPACE);
        return session;
    }

    private static final class RecordingPrompt implements UserApprovalPrompt {
        private final ApprovalResponse response;
        private int calls;

        private RecordingPrompt(ApprovalResponse response) {
            this.response = response;
        }

        @Override
        public ApprovalResponse requestApproval(Tool<?> tool, String input) {
            calls++;
            return response;
        }

        int calls() {
            return calls;
        }
    }

    private static final class MutatingTool implements Tool<ObjectNode> {
        @Override public String name() { return "mutating_tool"; }
        @Override public String description() { return "test mutating tool"; }
        @Override public Class<ObjectNode> inputType() { return ObjectNode.class; }
        @Override public boolean isReadOnly() { return false; }
        @Override public ObjectNode inputSchema(ObjectMapper mapper) { return mapper.createObjectNode(); }
        @Override public madacode.core.model.ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new madacode.core.model.ToolResult(name(), true, "ok");
        }
    }
}
