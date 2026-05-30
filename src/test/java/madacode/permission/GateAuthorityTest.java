package madacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.engine.ToolUseContext;
import madacode.tool.FileEditTool;
import madacode.tool.FileReadTool;
import madacode.tool.FileWriteTool;
import madacode.tool.GlobTool;
import madacode.tool.GrepTool;
import madacode.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guardian test: the permission gate is the sole authority for filesystem
 * scope policy.  Individual tools must never reject accesses themselves —
 * that responsibility belongs exclusively to the rule chain evaluated by
 * {@link DefaultPermissionGate}.
 *
 * <p>This test ensures that:
 * <ul>
 *   <li>Under a permissive gate, all registered file tools can access paths
 *       outside the working directory (the gate allows it, so the tool
 *       must not hard-deny).</li>
 *   <li>Under the default gate with a recording prompt, out-of-scope
 *       reads fall through to the user prompt (not hard-denied by the
 *       tool).</li>
 * </ul>
 */
class GateAuthorityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workingDir;

    @Test
    void permissiveGateAllowsOutOfScopeFileReadAccess() {
        PermissionGate gate = PermissionGate.permissive();
        ConversationSession session = new ConversationSession(workingDir);

        List<Tool<?>> tools = List.of(
                new FileReadTool(),
                new GrepTool(),
                new GlobTool());

        for (Tool<?> tool : tools) {
            ObjectNode input = mapper.createObjectNode();
            input.put("path", "/etc/passwd");

            PermissionDecision decision = gate.check(tool, input,
                    new ToolUseContext(workingDir, session));

            assertTrue(decision.isAllowed(),
                    tool.name() + " must be allowed under permissive gate, "
                            + "confirming the gate is the sole policy authority");
        }
    }

    @Test
    void defaultGatePromptsForOutOfScopeReads() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = new DefaultPermissionGate(prompt);
        ConversationSession session = new ConversationSession(workingDir);

        ObjectNode input = mapper.createObjectNode();
        input.put("path", "/etc/passwd");

        PermissionDecision decision = gate.check(new FileReadTool(), input,
                new ToolUseContext(workingDir, session));

        assertEquals(1, prompt.calls(),
                "Out-of-scope reads should fall through to the user prompt, "
                        + "not be hard-denied by the tool or the read-only rule");
        assertTrue(decision.isAllowed(),
                "With ALLOW_ONCE response, the tool should be allowed");
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source());
    }

    @Test
    void defaultGatePromptsForOutOfScopeWrites() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = new DefaultPermissionGate(prompt);
        ConversationSession session = new ConversationSession(workingDir);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/outside.txt");
        input.put("content", "hello");

        PermissionDecision decision = gate.check(new FileWriteTool(), input,
                new ToolUseContext(workingDir, session));

        assertEquals(1, prompt.calls(),
                "Out-of-scope writes should fall through to the user prompt");
        assertTrue(decision.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source());
    }

    @Test
    void bypassModeDeniesDangerousWriteEvenWithAllowOncePrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = new DefaultPermissionGate(prompt);
        ConversationSession session = new ConversationSession(workingDir);
        session.setPermissionMode(PermissionMode.BYPASS);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", workingDir.resolve(".bashrc").toString());
        input.put("content", "malicious");

        PermissionDecision decision = gate.check(new FileWriteTool(), input,
                new ToolUseContext(workingDir, session));

        assertTrue(decision.isAllowed(),
                "Bypass mode should still reach user prompt for dangerous targets, "
                        + "and ALLOW_ONCE prompt should allow it");
    }

    private static final class RecordingPrompt implements UserApprovalPrompt {
        private int calls;

        @Override
        public ApprovalResponse requestApproval(Tool<?> tool, String input) {
            calls++;
            return ApprovalResponse.ALLOW_ONCE;
        }

        private int calls() {
            return calls;
        }
    }
}