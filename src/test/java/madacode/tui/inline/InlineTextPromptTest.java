package madacode.tui.inline;

import madacode.tui.TextScreen;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineTextPromptTest {

    @Test
    void returnsEnteredTextOnEnter() throws Exception {
        assertEquals(Optional.of("hello"), simulate("hello\r", null));
    }

    @Test
    void supportsCursorMovementAndDeleteEditing() throws Exception {
        assertEquals(Optional.of("ac"), simulate("abc\033[D\033[D\033[3~\r", null));
    }

    @Test
    void escapeCancelsAndFiresInterrupt() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulate("\033", reason).isEmpty());
        assertEquals("esc", reason.get());
    }

    @Test
    void eofCancelsAndFiresInterrupt() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulate("", reason).isEmpty());
        assertEquals("eof", reason.get());
    }

    @Test
    void sanitizeReplacesLineBreaksAndTabsAndDropsOtherControls() {
        assertEquals("a b c d", InlineTextPrompt.sanitize("a\rb\nc\td\003"));
        assertEquals("", InlineTextPrompt.sanitize(null));
        assertEquals("", InlineTextPrompt.sanitize(""));
    }

    @Test
    void renderLinesPreservesInputLeadingAndTrailingSpaces() {
        LineEditor editor = new LineEditor();
        editor.set("  hi  ", 4);

        var lines = InlineTextPrompt.renderLines("prompt", editor, 80);

        assertEquals("prompt", lines.get(0));
        assertEquals("  hi█  ", lines.get(1));
    }

    private static Optional<String> simulate(
            String keystrokes,
            AtomicReference<String> reason) throws Exception {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .signalHandler(Terminal.SignalHandler.SIG_IGN)
                .streams(new ByteArrayInputStream(keystrokes.getBytes(StandardCharsets.UTF_8)), discard)
                .type(Terminal.TYPE_DUMB)
                .build()) {
            InlineTextPrompt prompt = new InlineTextPrompt(
                    new TextScreen(discard), terminal, null, reason == null ? null : reason::set);
            return prompt.read("prompt");
        } finally {
            discard.close();
        }
    }
}
