package madacode.tui.inline;

import madacode.tui.widget.ChoicePrompt;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InlineChoicePromptTest {

    private static final List<ChoicePrompt.Option<String>> OPTIONS = List.of(
            new ChoicePrompt.Option<>("alpha", "Alpha", "First option", ""),
            new ChoicePrompt.Option<>("beta", "Beta", "Second option", ""),
            new ChoicePrompt.Option<>("gamma", "Gamma", "Third option", "g"));

    private static ChoicePrompt.Model<String> model(int initial) {
        return new ChoicePrompt.Model<>("Pick one", "Choose wisely",
                OPTIONS, "↑/↓ select  Enter confirm  Esc cancel", initial);
    }

    @Test
    void selectsDefaultOnEnter() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "\r" // Enter immediately → selects "alpha"
        );
        assertTrue(result.isPresent());
        assertEquals("alpha", result.get());
    }

    @Test
    void arrowDownThenEnterSelectsSecond() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "\033[B" + // DOWN arrow → "beta"
                "\r"       // Enter
        );
        assertTrue(result.isPresent());
        assertEquals("beta", result.get());
    }

    @Test
    void arrowUpWrapsToLastThenEnter() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "\033[A" + // UP arrow from first → wraps to "gamma"
                "\r"       // Enter
        );
        assertTrue(result.isPresent());
        assertEquals("gamma", result.get());
    }

    @Test
    void hotkeyDigitSelectsOptionDirectly() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "3" +  // digit 3 → selects third option (1-based)
                "\r"   // Enter
        );
        assertTrue(result.isPresent());
        assertEquals("gamma", result.get());
    }

    @Test
    void escapeCancelsAndReturnsEmpty() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "\033" // ESC
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void escapeFiresInterruptCallback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        Optional<String> result = simulate(
                model(0),
                "\033",
                reason::set);
        assertTrue(result.isEmpty());
        assertEquals("esc", reason.get());
    }

    @Test
    void ctrlCCancelsAndReturnsEmpty() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "\003" // Ctrl-C
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void ctrlCFiresInterruptCallback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        Optional<String> result = simulate(
                model(0),
                "\003",
                reason::set);
        assertTrue(result.isEmpty());
        assertNotNull(reason.get());
    }

    @Test
    void lettersMatchHotkey() throws Exception {
        Optional<String> result = simulate(
                model(0),
                "g" +  // matches hotkey "g" of gamma
                "\r"   // Enter
        );
        assertTrue(result.isPresent());
        assertEquals("gamma", result.get());
    }

    // ---- test harness --------------------------------------------------

    /**
     * Run {@link InlineChoicePrompt#choose} with simulated keystrokes.
     *
     * @param model        the choice model
     * @param keystrokes   raw bytes to feed as terminal input
     * @return the selected value (may be empty)
     */
    private static Optional<String> simulate(ChoicePrompt.Model<String> model,
                                              String keystrokes) throws Exception {
        return simulate(model, keystrokes, null);
    }

    private static Optional<String> simulate(
            ChoicePrompt.Model<String> model,
            String keystrokes,
            java.util.function.Consumer<String> onInterrupt) throws Exception {
        // ByteArrayInputStream signals EOF cleanly when bytes are consumed, so
        // any read past the end returns -1 instead of blocking or throwing
        // "Write end dead" the way PipedInputStream does once its writer
        // thread exits.
        InputStream inputStream = new ByteArrayInputStream(
                keystrokes.getBytes(StandardCharsets.UTF_8));
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(inputStream, discard)
                .type(Terminal.TYPE_DUMB)
                .build()) {
            InlineChoicePrompt<String> prompt = new InlineChoicePrompt<>(
                    new madacode.tui.TextScreen(discard), terminal, null, onInterrupt);
            return prompt.choose(model);
        }
    }
}
