package madacode.permission;

import madacode.core.turn.CancellationToken;
import madacode.core.model.ToolResult;
import madacode.tool.Tool;
import madacode.tui.Screen;
import madacode.tui.Suspendable;
import madacode.tui.TextScreen;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.ColorPalette;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class JLineApprovalPromptTest {

    private static final Tool<ObjectNode> DUMMY_TOOL = new Tool<>() {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "Run shell commands"; }
        @Override public boolean isReadOnly() { return false; }
        @Override public ToolResult execute(ObjectNode input, madacode.core.engine.ToolUseContext ctx) { return null; }
        @Override public Class<ObjectNode> inputType() { return ObjectNode.class; }
        @Override public ObjectNode inputSchema(ObjectMapper mapper) { return mapper.createObjectNode(); }
    };

    @Test
    void enterSelectsAllowOnceByDefault() throws Exception {
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "ls -la",
                "\r" // Enter → allow once (first option, index 0)
        );
        assertEquals(ApprovalResponse.ALLOW_ONCE, response);
    }

    @Test
    void arrowLeftThenEnterSelectsDeny() throws Exception {
        // Options: allow once(0), allow session(1), deny(2). LEFT from 0 wraps to deny(2).
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "rm -rf /",
                "\033[D" + // LEFT → wraps from index 0 to index 2 (deny)
                "\r"       // Enter
        );
        assertEquals(ApprovalResponse.DENY, response);
    }

    @Test
    void arrowRightThenEnterSelectsAllowSession() throws Exception {
        // Default is allow once (index 0). RIGHT → allow session (index 1).
        ApprovalResponse response = simulate(
                DUMMY_TOOL,
                "echo hello",
                "\033[C" + // RIGHT → index 1 (allow session)
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
        // LEFT from index-0 wraps to deny (index 2); ENTER confirms.
        // Explicitly selecting deny means "deny this tool" — the turn continues.
        InterruptCapture capture = new InterruptCapture();
        ApprovalResponse response = simulateWithCallback(
                DUMMY_TOOL, "some command", "\033[D\r", capture);
        assertEquals(ApprovalResponse.DENY, response);
        assertNull(capture.capturedReason,
                "Navigating to Deny must not cancel the turn (only ESC/Ctrl+C should)");
    }

    @Test
    void hotkeyUsesActionMappingAndClearsModal() {
        RecordingScreen screen = new RecordingScreen();
        PauseCounter lock = new PauseCounter();
        try (RawTerminal terminal = new RawTerminal("s")) {
            JLineApprovalPrompt prompt = new JLineApprovalPrompt(screen, terminal, lock, null);
            ApprovalResponse response = prompt.requestApproval(DUMMY_TOOL, "echo hello");

            assertEquals(ApprovalResponse.ALLOW_SESSION, response);
            assertEquals(1, screen.setLiveModalCount);
            assertEquals(1, screen.clearLiveModalCount);
            assertEquals(1, lock.pauseCount);
            assertEquals(1, lock.resumeCount);
            assertEquals(1, terminal.enterRawModeCount);
            assertEquals(1, terminal.setAttributesCount);
            assertEquals(List.of(false, true), screen.cursorVisibilityChanges);
        }
    }

    @Test
    void eofClearsModalRestoresResourcesAndFiresInterrupt() {
        RecordingScreen screen = new RecordingScreen();
        PauseCounter lock = new PauseCounter();
        InterruptCapture capture = new InterruptCapture();
        try (RawTerminal terminal = new RawTerminal("")) {
            JLineApprovalPrompt prompt = new JLineApprovalPrompt(
                    screen, terminal, lock, reason -> capture.capturedReason = reason);
            ApprovalResponse response = prompt.requestApproval(DUMMY_TOOL, "echo hello");

            assertEquals(ApprovalResponse.DENY, response);
            assertEquals(1, screen.setLiveModalCount);
            assertEquals(1, screen.clearLiveModalCount);
            assertEquals(1, lock.pauseCount);
            assertEquals(1, lock.resumeCount);
            assertEquals(1, terminal.enterRawModeCount);
            assertEquals(1, terminal.setAttributesCount);
            assertEquals(List.of(false, true), screen.cursorVisibilityChanges);
            assertEquals(CancellationToken.REASON_PERMISSION_DENIED, capture.capturedReason);
        }
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

    private static final class PauseCounter implements Suspendable {
        int pauseCount;
        int resumeCount;

        @Override public void pause() { pauseCount++; }
        @Override public void resume() { resumeCount++; }
    }

    private static final class RecordingScreen implements Screen {
        int setLiveModalCount;
        int clearLiveModalCount;
        final java.util.ArrayList<Boolean> cursorVisibilityChanges = new java.util.ArrayList<>();

        @Override public void scrollback(List<String> lines) {}
        @Override public int width() { return 80; }
        @Override public int height() { return 24; }
        @Override public void flush() {}

        @Override
        public void setLiveModal(List<String> lines) {
            setLiveModalCount++;
        }

        @Override
        public void clearLiveModal() {
            clearLiveModalCount++;
        }

        @Override
        public void setCursorVisible(boolean visible) {
            cursorVisibilityChanges.add(visible);
        }
    }

    private static final class RawTerminal implements Terminal {
        private final InputStream input;
        private final PrintStream output;
        private final PrintWriter writer;
        private final NonBlockingReader reader;
        private final Map<Signal, SignalHandler> handlers = new EnumMap<>(Signal.class);
        private Attributes attributes = new Attributes();
        private Size size = new Size(80, 24);
        int enterRawModeCount;
        int setAttributesCount;

        RawTerminal(String input) {
            this.input = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            this.output = new PrintStream(OutputStream.nullOutputStream());
            this.writer = new PrintWriter(output, true);
            this.reader = NonBlocking.nonBlocking("approval-test", this.input, StandardCharsets.UTF_8);
        }

        @Override public String getName() { return "approval-test"; }

        @Override
        public SignalHandler handle(Signal signal, SignalHandler handler) {
            return handlers.put(signal, handler);
        }

        @Override
        public void raise(Signal signal) {
            SignalHandler handler = handlers.get(signal);
            if (handler != null) handler.handle(signal);
        }

        @Override public NonBlockingReader reader() { return reader; }
        @Override public PrintWriter writer() { return writer; }
        @Override public Charset encoding() { return StandardCharsets.UTF_8; }
        @Override public InputStream input() { return input; }
        @Override public OutputStream output() { return output; }
        @Override public boolean canPauseResume() { return false; }
        @Override public void pause() {}
        @Override public void pause(boolean wait) {}
        @Override public void resume() {}
        @Override public boolean paused() { return false; }

        @Override
        public Attributes enterRawMode() {
            enterRawModeCount++;
            return new Attributes(attributes);
        }

        @Override public boolean echo() { return false; }
        @Override public boolean echo(boolean echo) { return false; }
        @Override public Attributes getAttributes() { return new Attributes(attributes); }

        @Override
        public void setAttributes(Attributes attributes) {
            setAttributesCount++;
            this.attributes = new Attributes(attributes);
        }

        @Override public Size getSize() { return new Size(size.getColumns(), size.getRows()); }
        @Override public void setSize(Size size) { this.size = new Size(size.getColumns(), size.getRows()); }
        @Override public void flush() { output.flush(); }
        @Override public String getType() { return Terminal.TYPE_DUMB; }
        @Override public boolean puts(InfoCmp.Capability capability, Object... params) { return false; }
        @Override public boolean getBooleanCapability(InfoCmp.Capability capability) { return false; }
        @Override public Integer getNumericCapability(InfoCmp.Capability capability) { return null; }
        @Override public String getStringCapability(InfoCmp.Capability capability) { return null; }
        @Override public Cursor getCursorPosition(IntConsumer discarded) { return new Cursor(0, 0); }
        @Override public boolean hasMouseSupport() { return false; }
        @Override public boolean trackMouse(MouseTracking tracking) { return false; }
        @Override public MouseEvent readMouseEvent() { return null; }
        @Override public MouseEvent readMouseEvent(IntSupplier reader) { return null; }
        @Override public boolean hasFocusSupport() { return false; }
        @Override public boolean trackFocus(boolean tracking) { return false; }
        @Override public ColorPalette getPalette() { return ColorPalette.DEFAULT; }

        @Override
        public void close() {
            reader.shutdown();
            writer.close();
            output.close();
        }
    }
}
