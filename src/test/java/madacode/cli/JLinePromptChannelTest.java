package madacode.cli;

import madacode.tui.Suspendable;
import madacode.tui.TextScreen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JLinePromptChannelTest {

    private FakeLineReader reader;
    private PauseCounter pauseCounter;
    private JLinePromptChannel channel;
    private Terminal terminal;
    private PrintStream discard;

    @BeforeEach
    void setUp() throws Exception {
        reader = new FakeLineReader();
        pauseCounter = new PauseCounter();
        discard = new PrintStream(OutputStream.nullOutputStream());
        terminal = dumbTerminal("", discard);
        channel = new JLinePromptChannel(reader, new TextScreen(discard), terminal, pauseCounter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (terminal != null) terminal.close();
        if (discard != null) discard.close();
    }

    @Test
    void isAvailable_returns_true_after_construction() {
        assertTrue(channel.isAvailable());
    }

    // ---- chooseOne --------------------------------------------------------

    @Test
    void chooseOne_returns_label_when_user_selects_with_arrow_keys() throws Exception {
        var result = simulateChooseOne("pick", options("Alpha", "Beta", "Gamma"), "\033[B\r");
        assertTrue(result.isPresent());
        assertEquals("Beta", result.get());
    }

    @Test
    void chooseOne_returns_default_label_on_enter() throws Exception {
        var result = simulateChooseOne("pick", options("A", "B"), "\r");
        assertTrue(result.isPresent());
        assertEquals("A", result.get());
    }

    @Test
    void chooseOne_returns_empty_on_escape() throws Exception {
        assertTrue(simulateChooseOne("pick", options("A", "B"), "\033").isEmpty());
    }

    @Test
    void chooseOne_escape_fires_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulateChooseOne(
                "pick", options("A", "B"), "\033", new PauseCounter(), reason::set).isEmpty());
        assertEquals("esc", reason.get());
    }

    @Test
    void chooseOne_enter_selection_does_not_fire_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        var result = simulateChooseOne(
                "pick", options("A", "B"), "\r", new PauseCounter(), reason::set);
        assertEquals(Optional.of("A"), result);
        assertEquals(null, reason.get());
    }

    // ---- chooseMany -------------------------------------------------------

    @Test
    void chooseMany_parses_comma_separated_indices() {
        reader.queueResponse("1,3");
        var result = channel.chooseMany("pick", options("Alpha", "Beta", "Gamma"));
        assertTrue(result.isPresent());
        assertEquals(List.of("Alpha", "Gamma"), result.get());
    }

    @Test
    void chooseMany_returns_empty_when_no_valid_index() {
        reader.queueResponse("99");
        assertTrue(channel.chooseMany("pick", options("Alpha", "Beta")).isEmpty());
    }

    @Test
    void chooseMany_returns_empty_on_blank_input() {
        reader.queueResponse("");
        assertTrue(channel.chooseMany("pick", options("A", "B")).isEmpty());
    }

    // ---- freeText ---------------------------------------------------------

    @Test
    void freeText_returns_trimmed_input() {
        reader.queueResponse("  hello world  ");
        var result = channel.freeText("enter:");
        assertTrue(result.isPresent());
        assertEquals("hello world", result.get());
    }

    @Test
    void freeText_returns_empty_on_blank() {
        reader.queueResponse("   ");
        assertTrue(channel.freeText("enter:").isEmpty());
    }

    @Test
    void freeText_null_input_fires_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        FakeLineReader eofReader = new FakeLineReader().queueResponse(null);
        try (Terminal terminal = dumbTerminal("", discard)) {
            JLinePromptChannel ch = new JLinePromptChannel(
                    eofReader, new TextScreen(discard), terminal, pauseCounter, reason::set);
            assertTrue(ch.freeText("enter:").isEmpty());
        }
        assertEquals("eof", reason.get());
    }

    @Test
    void freeText_blank_input_does_not_fire_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        FakeLineReader blankReader = new FakeLineReader().queueResponse("");
        try (Terminal terminal = dumbTerminal("", discard)) {
            JLinePromptChannel ch = new JLinePromptChannel(
                    blankReader, new TextScreen(discard), terminal, pauseCounter, reason::set);
            assertTrue(ch.freeText("enter:").isEmpty());
        }
        assertEquals(null, reason.get());
    }

    @Test
    void freeText_ctrl_c_fires_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        FakeLineReader throwing = new FakeLineReader() {
            @Override
            public String readLine(String prompt) {
                throw new UserInterruptException("ctrl-c");
            }
        };
        try (Terminal terminal = dumbTerminal("", discard)) {
            JLinePromptChannel ch = new JLinePromptChannel(
                    throwing, new TextScreen(discard), terminal, pauseCounter, reason::set);
            assertTrue(ch.freeText("enter:").isEmpty());
        }
        assertEquals("sigint", reason.get());
    }

    // ---- confirm ----------------------------------------------------------

    @Test
    void confirm_returns_true_for_lowercase_y() {
        reader.queueResponse("y");
        assertTrue(channel.confirm("proceed?"));
    }

    @Test
    void confirm_returns_true_for_uppercase_Y() {
        reader.queueResponse("Y");
        assertTrue(channel.confirm("proceed?"));
    }

    @Test
    void confirm_returns_false_for_blank() {
        reader.queueResponse("   ");
        assertFalse(channel.confirm("proceed?"));
    }

    @Test
    void confirm_returns_false_for_null() {
        reader.queueResponse(null);
        assertFalse(channel.confirm("proceed?"));
    }

    @Test
    void confirm_returns_false_for_n() {
        reader.queueResponse("n");
        assertFalse(channel.confirm("proceed?"));
    }

    // ---- pause/resume lifecycle -------------------------------------------

    @Test
    void pause_and_resume_called_around_chooseOne() throws Exception {
        PauseCounter counter = new PauseCounter();
        simulateChooseOne("pick", options("A"), "\r", counter);
        assertEquals(1, counter.pauseCount, "pause must be called once");
        assertEquals(1, counter.resumeCount, "resume must be called once");
    }

    @Test
    void resume_called_even_when_readLine_throws() {
        // Override to throw EndOfFileException
        FakeLineReader throwing = new FakeLineReader() {
            @Override
            public String readLine(String prompt) {
                throw new EndOfFileException();
            }
        };
        JLinePromptChannel ch = new JLinePromptChannel(
                throwing, new TextScreen(discard), terminal, pauseCounter);

        try {
            ch.freeText("enter:");
        } catch (EndOfFileException ignored) {
        }

        assertEquals(1, pauseCounter.pauseCount,  "pause must be called");
        assertEquals(1, pauseCounter.resumeCount, "resume must be called in finally");
    }

    // ---- helpers ----------------------------------------------------------

    private static final class PauseCounter implements Suspendable {
        int pauseCount;
        int resumeCount;

        @Override public void pause()  { pauseCount++; }
        @Override public void resume() { resumeCount++; }
    }

    private static List<UserPromptChannel.ChannelOption> options(String... labels) {
        return java.util.Arrays.stream(labels)
                .map(label -> new UserPromptChannel.ChannelOption(label, ""))
                .toList();
    }

    private static Optional<String> simulateChooseOne(
            String title, List<UserPromptChannel.ChannelOption> options,
            String keystrokes) throws Exception {
        return simulateChooseOne(title, options, keystrokes, new PauseCounter());
    }

    private static Optional<String> simulateChooseOne(
            String title, List<UserPromptChannel.ChannelOption> options,
            String keystrokes, PauseCounter pauseCounter) throws Exception {
        return simulateChooseOne(title, options, keystrokes, pauseCounter, null);
    }

    private static Optional<String> simulateChooseOne(
            String title, List<UserPromptChannel.ChannelOption> options,
            String keystrokes, PauseCounter pauseCounter,
            Consumer<String> onInterrupt) throws Exception {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        try (Terminal terminal = dumbTerminal(keystrokes, discard)) {
            JLinePromptChannel channel = new JLinePromptChannel(
                    new FakeLineReader(), new TextScreen(discard), terminal, pauseCounter, onInterrupt);
            return channel.chooseOne(title, options);
        } finally {
            discard.close();
        }
    }

    private static Terminal dumbTerminal(String keystrokes, PrintStream out) throws Exception {
        return TerminalBuilder.builder()
                .system(false)
                .streams(new ByteArrayInputStream(keystrokes.getBytes(StandardCharsets.UTF_8)), out)
                .type(Terminal.TYPE_DUMB)
                .build();
    }
}
