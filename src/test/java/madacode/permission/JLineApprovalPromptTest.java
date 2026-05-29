package madacode.permission;

import madacode.core.CancellationToken;
import madacode.core.ToolResult;
import madacode.tool.Tool;
import madacode.tui.TextScreen;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JLineApprovalPromptTest {

    private static final Tool<ObjectNode> DUMMY_TOOL = new Tool<>() {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "Run shell commands"; }
        @Override public boolean isReadOnly() { return false; }
        @Override public ToolResult execute(ObjectNode input, madacode.core.ToolUseContext ctx) { return null; }
        @Override public Class<ObjectNode> inputType() { return ObjectNode.class; }
        @Override public ObjectNode inputSchema(ObjectMapper mapper) { return mapper.createObjectNode(); }
    };

    @Test
    void enterSelectsAllowOnceByDefault() throws Exception {
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "ls -la",
                "\r" // Enter → Allow once (first option, index 0)
        );
        assertEquals(ApprovalResponse.ALLOW_ONCE, response);
    }

    @Test
    void arrowUpThenEnterSelectsDeny() throws Exception {
        // Options: Allow once(0), Allow session(1), Deny(2). UP from 0 wraps to Deny(2).
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "rm -rf /",
                "\033[A" + // UP → wraps from index 0 to index 2 (Deny)
                "\r"       // Enter
        );
        assertEquals(ApprovalResponse.DENY, response);
    }

    @Test
    void arrowDownThenEnterSelectsAllowSession() throws Exception {
        // Default is Allow once (index 0). DOWN → Allow session (index 1).
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "echo hello",
                "\033[B" + // DOWN → index 1 (Allow for session)
                "\r"       // Enter
        );
        assertEquals(ApprovalResponse.ALLOW_SESSION, response);
    }

    @Test
    void escapeCancelsAndReturnsDeny() throws Exception {
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "some command",
                "\033" // ESC
        );
        assertEquals(ApprovalResponse.DENY, response);
    }

    @Test
    void ctrlCCancelsAndReturnsDeny() throws Exception {
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "some command",
                "\003" // Ctrl-C
        );
        assertEquals(ApprovalResponse.DENY, response);
    }

    @Test
    void nullToolDoesNotCrash() throws Exception {
        ApprovalResponse response = simulate(
                null,
                "some input",
                "\r" // Enter
        );
        assertEquals(ApprovalResponse.ALLOW_ONCE, response);
    }

    @Test
    void nullInputShowsNoInputPlaceholder() throws Exception {
        // Shouldn't crash with null input
        ApprovalResponse response = simulate(
                DUMMY_TOOL, null, "\r");
        assertEquals(ApprovalResponse.ALLOW_ONCE, response);
    }

    // ---- interrupt callback tests ----------------------------------------

    @Test
    void esc_in_modal_fires_interrupt_callback_and_returns_deny() throws Exception {
        InterruptCapture capture = new InterruptCapture();
        ApprovalResponse response = simulateWithCallback(
                DUMMY_TOOL, "some command", "\033", capture);
        assertEquals(ApprovalResponse.DENY, response);
        assertNotNull(capture.capturedReason, "onInterrupt callback must have been called");
        assertEquals(CancellationToken.REASON_PERMISSION_DENIED, capture.capturedReason);
    }

    @Test
    void ctrl_c_in_modal_fires_interrupt_callback_and_returns_deny() throws Exception {
        InterruptCapture capture = new InterruptCapture();
        ApprovalResponse response = simulateWithCallback(
                DUMMY_TOOL, "some command", "\003", capture);
        assertEquals(ApprovalResponse.DENY, response);
        assertNotNull(capture.capturedReason,
                "Ctrl+C must fire the interrupt callback just like ESC");
    }

    @Test
    void allow_once_does_not_fire_interrupt_callback() throws Exception {
        InterruptCapture capture = new InterruptCapture();
        ApprovalResponse response = simulateWithCallback(
                DUMMY_TOOL, "some command", "\r", capture);
        assertEquals(ApprovalResponse.ALLOW_ONCE, response);
        assertNull(capture.capturedReason,
                "Allowing a tool must never trigger the interrupt callback");
    }

    @Test
    void explicit_deny_via_navigation_does_not_fire_interrupt_callback() throws Exception {
        // UP from index-0 wraps to Deny (index 2); ENTER confirms.
        // Explicitly selecting Deny means "deny this tool" — the turn continues.
        InterruptCapture capture = new InterruptCapture();
        ApprovalResponse response = simulateWithCallback(
                DUMMY_TOOL, "some command", "\033[A\r", capture);
        assertEquals(ApprovalResponse.DENY, response);
        assertNull(capture.capturedReason,
                "Navigating to Deny must not cancel the turn (only ESC/Ctrl+C should)");
    }

    // ---- test harness --------------------------------------------------

    private static ApprovalResponse simulate(Tool<?> tool, String input,
                                              String keystrokes) throws Exception {
        return simulateWithCallback(tool, input, keystrokes, null);
    }

    private static ApprovalResponse simulateWithCallback(Tool<?> tool, String input,
                                                          String keystrokes,
                                                          InterruptCapture capture) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(
                keystrokes.getBytes(StandardCharsets.UTF_8));
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(inputStream, discard)
                .type(Terminal.TYPE_DUMB)
                .build()) {
            java.util.function.Consumer<String> callback =
                    capture == null ? null : reason -> capture.capturedReason = reason;
            JLineApprovalPrompt prompt = new JLineApprovalPrompt(
                    new TextScreen(new PrintStream(OutputStream.nullOutputStream())),
                    terminal, null, callback);
            return prompt.requestApproval(tool, input);
        }
    }

    private static final class InterruptCapture {
        volatile String capturedReason;
    }
}
