package madacode.cli;

import madacode.tui.Suspendable;
import madacode.tui.TextScreen;

import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.ColorPalette;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JLinePromptChannelTest {

    @Test
    void isAvailable_returns_true_after_construction() throws Exception {
        assertEquals(Boolean.TRUE, simulate("", channel -> channel.isAvailable()));
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
    void chooseMany_parses_comma_separated_indices() throws Exception {
        var result = simulate(
                "1,3\r",
                channel -> channel.chooseMany("pick", options("Alpha", "Beta", "Gamma")));
        assertTrue(result.isPresent());
        assertEquals(List.of("Alpha", "Gamma"), result.get());
    }

    @Test
    void chooseMany_returns_empty_when_no_valid_index() throws Exception {
        assertTrue(simulate(
                "99\r",
                channel -> channel.chooseMany("pick", options("Alpha", "Beta"))).isEmpty());
    }

    @Test
    void chooseMany_returns_empty_on_blank_input() throws Exception {
        assertTrue(simulate(
                "\r",
                channel -> channel.chooseMany("pick", options("A", "B"))).isEmpty());
    }

    // ---- freeText ---------------------------------------------------------

    @Test
    void freeText_returns_trimmed_input() throws Exception {
        var result = simulate(
                "  hello world  \r",
                channel -> channel.freeText("enter:"));
        assertTrue(result.isPresent());
        assertEquals("hello world", result.get());
    }

    @Test
    void freeText_returns_empty_on_blank() throws Exception {
        assertTrue(simulate(
                "   \r",
                channel -> channel.freeText("enter:")).isEmpty());
    }

    @Test
    void freeText_eof_fires_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulate(
                "",
                channel -> channel.freeText("enter:"),
                new PauseCounter(),
                reason::set).isEmpty());
        assertEquals("eof", reason.get());
    }

    @Test
    void freeText_blank_input_does_not_fire_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulate(
                "   \r",
                channel -> channel.freeText("enter:"),
                new PauseCounter(),
                reason::set).isEmpty());
        assertEquals(null, reason.get());
    }

    @Test
    void freeText_ctrl_c_fires_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulate(
                "\003",
                channel -> channel.freeText("enter:"),
                new PauseCounter(),
                reason::set).isEmpty());
        assertEquals("sigint", reason.get());
    }

    @Test
    void freeText_escape_fires_interrupt_callback() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        assertTrue(simulate(
                "\033",
                channel -> channel.freeText("enter:"),
                new PauseCounter(),
                reason::set).isEmpty());
        assertEquals("esc", reason.get());
    }

    // ---- sensitiveText ----------------------------------------------------

    @Test
    void sensitiveText_preserves_surrounding_spaces() throws Exception {
        var result = simulate(
                "  sk-with-spaces  \r",
                channel -> channel.sensitiveText("token:"));
        assertTrue(result.isPresent());
        assertEquals("  sk-with-spaces  ", result.get());
    }

    @Test
    void sensitiveText_returns_empty_on_blank() throws Exception {
        assertTrue(simulate(
                "   \r",
                channel -> channel.sensitiveText("token:")).isEmpty());
    }

    // ---- confirm ----------------------------------------------------------

    @Test
    void confirm_returns_true_for_default_yes_on_enter() throws Exception {
        assertEquals(Boolean.TRUE, simulate("\r", channel -> channel.confirm("proceed?")));
    }

    @Test
    void confirm_returns_false_when_no_is_selected() throws Exception {
        assertEquals(Boolean.FALSE, simulate("\033[B\r", channel -> channel.confirm("proceed?")));
    }

    @Test
    void confirm_returns_false_on_escape() throws Exception {
        assertEquals(Boolean.FALSE, simulate("\033", channel -> channel.confirm("proceed?")));
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
    void resume_called_even_on_eof() throws Exception {
        PauseCounter counter = new PauseCounter();
        var result = simulate("", channel -> channel.freeText("enter:"), counter, null);
        assertTrue(result.isEmpty());
        assertEquals(1, counter.pauseCount, "pause must be called");
        assertEquals(1, counter.resumeCount, "resume must be called in finally");
    }

    // ---- helpers ----------------------------------------------------------

    private static final class PauseCounter implements Suspendable {
        int pauseCount;
        int resumeCount;

        @Override public void pause()  { pauseCount++; }
        @Override public void resume() { resumeCount++; }
    }

    @FunctionalInterface
    private interface ChannelCall<T> {
        T run(JLinePromptChannel channel);
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
        return simulate(
                keystrokes,
                channel -> channel.chooseOne(title, options),
                pauseCounter,
                onInterrupt);
    }

    private static <T> T simulate(String keystrokes, ChannelCall<T> call) throws Exception {
        return simulate(keystrokes, call, new PauseCounter(), null);
    }

    private static <T> T simulate(
            String keystrokes,
            ChannelCall<T> call,
            PauseCounter pauseCounter,
            Consumer<String> onInterrupt) throws Exception {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        try (Terminal terminal = rawTerminal(keystrokes, discard)) {
            JLinePromptChannel channel = new JLinePromptChannel(
                    new TextScreen(discard), terminal, pauseCounter, onInterrupt);
            return call.run(channel);
        } finally {
            discard.close();
        }
    }

    private static Terminal rawTerminal(String keystrokes, PrintStream out) {
        return new RawTerminal(keystrokes, out);
    }

    private static final class RawTerminal implements Terminal {
        private final InputStream input;
        private final PrintStream output;
        private final PrintWriter writer;
        private final NonBlockingReader reader;
        private final Map<Signal, SignalHandler> handlers = new EnumMap<>(Signal.class);
        private Attributes attributes = new Attributes();
        private Size size = new Size(80, 24);

        RawTerminal(String input, PrintStream output) {
            this.input = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            this.output = output;
            this.writer = new PrintWriter(output, true);
            this.reader = NonBlocking.nonBlocking("raw-test", this.input, StandardCharsets.UTF_8);
        }

        @Override public String getName() { return "raw-test"; }

        @Override
        public SignalHandler handle(Signal signal, SignalHandler handler) {
            return handlers.put(signal, handler);
        }

        @Override
        public void raise(Signal signal) {
            SignalHandler handler = handlers.get(signal);
            if (handler != null) {
                handler.handle(signal);
            }
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
            return new Attributes(attributes);
        }

        @Override public boolean echo() { return false; }
        @Override public boolean echo(boolean echo) { return false; }
        @Override public Attributes getAttributes() { return new Attributes(attributes); }

        @Override
        public void setAttributes(Attributes attributes) {
            this.attributes = new Attributes(attributes);
        }

        @Override public Size getSize() { return new Size(size.getColumns(), size.getRows()); }

        @Override
        public void setSize(Size size) {
            this.size = new Size(size.getColumns(), size.getRows());
        }

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
        }
    }
}
