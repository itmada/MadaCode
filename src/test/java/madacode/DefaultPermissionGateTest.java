package madacode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ConversationSession;
import madacode.core.ToolUseContext;
import madacode.events.AppEvent;
import madacode.events.AppEventPublisher;
import madacode.events.AppEvents;
import madacode.events.AuditEvent;
import madacode.permission.ApprovalResponse;
import madacode.permission.BashSafetyPermissionRule;
import madacode.tool.BashTool;
import madacode.permission.DefaultPermissionGate;
import madacode.tool.FileReadTool;
import madacode.permission.PermissionDecision;
import madacode.permission.ReadOnlyPermissionRule;
import madacode.permission.UserApprovalPrompt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.nio.file.Path;
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

        PermissionDecision decision = gate.check(new FileReadTool(), fileReadInput(), context());

        assertTrue(decision.isAllowed());
        assertEquals(ReadOnlyPermissionRule.SOURCE, decision.source());
        assertEquals(0, prompt.calls());
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

    private ObjectNode bashInput(String command) {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return input;
    }

    private ObjectNode fileReadInput() {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "README.md");
        return input;
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, new ConversationSession(tempDir));
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
