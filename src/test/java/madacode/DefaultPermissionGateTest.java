package madacode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.engine.ToolUseContext;
import madacode.events.AppEvent;
import madacode.events.AppEventPublisher;
import madacode.events.AppEvents;
import madacode.events.AuditEvent;
import madacode.permission.AcceptEditsPermissionRule;
import madacode.permission.ApprovalResponse;
import madacode.permission.BashSafetyPermissionRule;
import madacode.permission.BypassPermissionRule;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionDecision;
import madacode.permission.PermissionMode;
import madacode.permission.ReadOnlyPermissionRule;
import madacode.permission.UserApprovalPrompt;
import madacode.tool.BashTool;
import madacode.tool.FileEditTool;
import madacode.tool.FileReadTool;
import madacode.tool.FileWriteTool;
import madacode.tool.GlobTool;
import madacode.tool.GrepTool;
import madacode.tool.MadaPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DefaultPermissionGateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readOnlyToolsAreAllowedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(new FileReadTool(), fileReadInput("README.md"), context());

        assertTrue(decision.isAllowed());
        assertEquals(ReadOnlyPermissionRule.SOURCE, decision.source());
        assertEquals(0, prompt.calls());
    }

    @Test
    void readOnlyToolOutsideWorkingDirPromptsUser() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(new FileReadTool(), fileReadInput("/etc/passwd"), context());

        assertTrue(decision.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source());
        assertEquals(1, prompt.calls());
    }

    @Test
    void dangerousBashCommandsAreDeniedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(new BashTool(), bashInput("rm -rf /"), context());

        assertFalse(decision.isAllowed());
        assertEquals(BashSafetyPermissionRule.SOURCE, decision.source());
        assertTrue(decision.reason().contains("Dangerous bash command denied"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void ordinaryBashCommandUsesUserPrompt() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(new BashTool(), bashInput("echo hi"), context());

        assertTrue(decision.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source());
        assertEquals(1, prompt.calls());
    }

    @Test
    void permissionDecisionsPublishAuditEvents() {
        CapturingPublisher publisher = new CapturingPublisher();
        AppEvents.install(publisher);
        try {
            RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
            DefaultPermissionGate gate = gate(prompt);

            PermissionDecision decision = gate.check(new BashTool(), bashInput("echo hi"), context());

            assertTrue(decision.isAllowed());
            AuditEvent audit = publisher.events.stream()
                    .filter(AuditEvent.class::isInstance)
                    .map(AuditEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("bash", audit.tool());
            assertTrue(audit.allowed());
            assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, audit.permissionSource());
            assertTrue(audit.inputPreview().contains("echo hi"));
        } finally {
            AppEvents.resetForTests();
        }
    }

    @Test
    void allowSessionOnlyRemembersTheSameToolInput() {
        RecordingPrompt prompt = new RecordingPrompt(
                ApprovalResponse.ALLOW_SESSION,
                ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision first = gate.check(new BashTool(), bashInput("echo hi"), context());
        PermissionDecision second = gate.check(new BashTool(), bashInput("echo hi"), context());
        PermissionDecision third = gate.check(new BashTool(), bashInput("echo bye"), context());

        assertTrue(first.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, first.source());
        assertTrue(second.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_SESSION_MEMORY, second.source());
        assertTrue(third.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, third.source());
        assertEquals(2, prompt.calls());
    }

    @Test
    void allowSessionDoesNotBypassBashSafetyRules() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_SESSION);
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision safe = gate.check(new BashTool(), bashInput("echo hi"), context());
        PermissionDecision dangerous = gate.check(new BashTool(), bashInput("rm -rf /"), context());

        assertTrue(safe.isAllowed());
        assertFalse(dangerous.isAllowed());
        assertEquals(BashSafetyPermissionRule.SOURCE, dangerous.source());
        assertEquals(1, prompt.calls());
    }

    @Test
    void bypassModeAllowsOrdinaryFileEdit() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.BYPASS);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.resolve("test.txt").toString());
        input.put("content", "hello");

        PermissionDecision decision = gate.check(new FileWriteTool(), input, new ToolUseContext(tempDir, session));

        assertTrue(decision.isAllowed());
        assertEquals(BypassPermissionRule.SOURCE, decision.source());
        assertEquals(0, prompt.calls());
    }

    @Test
    void bypassModeBlocksDangerousEditTarget() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.DENY);
        DefaultPermissionGate gate = gate(prompt);
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.BYPASS);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.resolve(".bashrc").toString());
        input.put("content", "malicious");

        PermissionDecision decision = gate.check(new FileWriteTool(), input, new ToolUseContext(tempDir, session));

        assertFalse(decision.isAllowed(),
                "Bypass mode must not auto-allow writes to .bashrc");
        assertEquals(1, prompt.calls(),
                "Dangerous edit target should fall through to prompt");
    }

    @Test
    void bypassModeDeniesGenericWritesToLongRunningTaskState() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.BYPASS);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir
                .resolve(".mada/long-running/task-001/feature_list.json")
                .toString());
        input.put("content", "[]");

        PermissionDecision decision = gate.check(new FileWriteTool(), input, new ToolUseContext(tempDir, session));

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("longrun_task_update"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void bashCannotMutateLongRunningTaskStateThroughGenericCommand() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.BYPASS);

        String target = tempDir
                .resolve(".mada/long-running/task-001/progress.txt")
                .toString();
        PermissionDecision decision = gate.check(
                new BashTool(),
                bashInput("printf 'done' >> " + target),
                new ToolUseContext(tempDir, session));

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("longrun_task_update"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void acceptEditsModeAutoAllowsFileEditInsideWorkingDir() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.resolve("test.txt").toString());
        input.put("content", "hello");

        PermissionDecision decision = gate.check(new FileWriteTool(), input, new ToolUseContext(tempDir, session));

        assertTrue(decision.isAllowed());
        assertEquals(AcceptEditsPermissionRule.SOURCE, decision.source());
        assertEquals(0, prompt.calls());
    }

    @Test
    void acceptEditsModeBlocksDangerousEditTarget() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.DENY);
        DefaultPermissionGate gate = gate(prompt);
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.resolve(".bashrc").toString());
        input.put("content", "malicious");

        PermissionDecision decision = gate.check(new FileEditTool(), input, new ToolUseContext(tempDir, session));

        assertFalse(decision.isAllowed(),
                "Accept-edits mode must not auto-allow writes to .bashrc");
        assertEquals(1, prompt.calls());
    }

    @Test
    void permissiveGateAllowsEverythingIncludingDangerousTargets() {
        madacode.permission.PermissionGate gate = madacode.permission.PermissionGate.permissive();
        ConversationSession session = new ConversationSession(tempDir);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", tempDir.resolve(".bashrc").toString());
        input.put("content", "malicious");

        PermissionDecision decision = gate.check(new FileWriteTool(), input, new ToolUseContext(tempDir, session));

        assertTrue(decision.isAllowed(),
                "Permissive gate must allow everything — it intentionally skips filesystem policy");
    }

    @Test
    void defaultGateBlocksFileEditOutsideWorkingDir() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/outside.txt");
        input.put("content", "hello");

        PermissionDecision decision = gate.check(new FileWriteTool(), input, context());

        assertTrue(decision.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source(),
                "File edit outside working dir should fall through to user prompt");
        assertEquals(1, prompt.calls());
    }

    @Test
    void readOnlyToolWithBlobTrustedRootIsAllowed(@TempDir Path blobsDir) {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = new DefaultPermissionGate(prompt, List.of(blobsDir));

        ObjectNode input = mapper.createObjectNode();
        input.put("path", blobsDir.resolve("data.bin").toString());

        PermissionDecision decision = gate.check(new FileReadTool(), input, context());

        assertTrue(decision.isAllowed());
        assertEquals(ReadOnlyPermissionRule.SOURCE, decision.source());
        assertEquals(0, prompt.calls());
    }

    @Test
    void dangerousBashWriteToBashrcIsDeniedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("echo malicious >> ~/.bashrc"), context());

        assertFalse(decision.isAllowed());
        assertEquals(BashSafetyPermissionRule.SOURCE, decision.source());
        assertTrue(decision.reason().contains("sensitive"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void dangerousBashWriteToZshrcIsDeniedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("echo malicious >> ~/.zshrc"), context());

        assertFalse(decision.isAllowed());
        assertEquals(BashSafetyPermissionRule.SOURCE, decision.source());
    }

    @Test
    void dangerousBashWriteToGitconfigIsDeniedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("echo malicious >> ~/.gitconfig"), context());

        assertFalse(decision.isAllowed());
        assertEquals(BashSafetyPermissionRule.SOURCE, decision.source());
    }

    @Test
    void dangerousBashWriteToHomeBashrcWithHomeEnvIsDeniedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("echo malicious >> $HOME/.bashrc"), context());

        assertFalse(decision.isAllowed());
        assertEquals(BashSafetyPermissionRule.SOURCE, decision.source());
    }

    @Test
    void ordinaryBashWriteToProjectFileIsAllowedWithPrompt() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("echo hello >> project/README.md"), context());

        assertTrue(decision.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source());
        assertEquals(1, prompt.calls());
    }

    // ---- Bash protection for long-running state files ----

    @Test
    void bashCatOfTaskJsonIsDeniedWithoutPrompt() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        String target = tempDir.resolve(".mada/long-running/task-1/task.json").toString();
        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("cat " + target), context());

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("longrun_task_update"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void bashDdOfTaskJsonIsDenied() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        String target = tempDir.resolve(".mada/long-running/task-1/task.json").toString();
        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("dd of=" + target), context());

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("longrun_task_update"));
        assertEquals(0, prompt.calls());
    }

    @Test
    void bashRubyWithKnownIssuesJsonIsDenied() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        String target = tempDir.resolve(".mada/long-running/task-1/known-issues.json").toString();
        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("ruby -e 'File.write(\"" + target + "\", \"[]\")'"), context());

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("longrun_task_update"));
    }

    @Test
    void bashShCcatOfProgressTxtIsDenied() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        String target = tempDir.resolve(".mada/long-running/task-1/progress.txt").toString();
        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("sh -c 'cat " + target + "'"), context());

        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("longrun_task_update"));
    }

    @Test
    void bashLsLongRunningDirIsAllowed() {
        RecordingPrompt prompt = new RecordingPrompt(ApprovalResponse.ALLOW_ONCE);
        DefaultPermissionGate gate = gate(prompt);

        PermissionDecision decision = gate.check(
                new BashTool(), bashInput("ls .mada/long-running"), context());

        assertTrue(decision.isAllowed());
        assertEquals(DefaultPermissionGate.SOURCE_USER_PROMPT, decision.source());
        assertEquals(1, prompt.calls());
    }

    @Test
    void fileReadOfCoreStateFileIsAllowed() {
        RecordingPrompt prompt = new RecordingPrompt();
        DefaultPermissionGate gate = gate(prompt);

        ObjectNode input = mapper.createObjectNode();
        input.put("path", tempDir.resolve(".mada/long-running/task-1/task.json").toString());

        PermissionDecision decision = gate.check(new FileReadTool(), input, context());

        assertTrue(decision.isAllowed());
        assertEquals(0, prompt.calls());
    }

    private ObjectNode bashInput(String command) {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return input;
    }

    private ObjectNode fileReadInput(String path) {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", path);
        return input;
    }

    private ToolUseContext context() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.DEFAULT);
        return new ToolUseContext(tempDir, session);
    }

    private DefaultPermissionGate gate(RecordingPrompt prompt) {
        return new DefaultPermissionGate(prompt);
    }

    private static final class CapturingPublisher implements AppEventPublisher {
        private final AtomicLong sequence = new AtomicLong();
        private final List<AppEvent> events = new ArrayList<>();

        @Override
        public void publish(AppEvent event) {
            events.add(event);
        }

        @Override
        public long nextSequence() {
            return sequence.incrementAndGet();
        }

        @Override
        public void flush(Duration timeout) {
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingPrompt implements UserApprovalPrompt {

        private final Queue<ApprovalResponse> responses = new ArrayDeque<>();
        private int calls;

        private RecordingPrompt(ApprovalResponse... responses) {
            this.responses.addAll(java.util.List.of(responses));
        }

        @Override
        public ApprovalResponse requestApproval(madacode.tool.Tool tool, String input) {
            calls++;
            return responses.isEmpty() ? ApprovalResponse.DENY : responses.remove();
        }

        private int calls() {
            return calls;
        }
    }
}
