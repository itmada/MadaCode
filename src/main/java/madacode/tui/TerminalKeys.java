package madacode.tui;

import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared low-level terminal keystroke decoder.
 *
 * <p>Consolidates the ESC / arrow / control-char parsing that was previously
 * duplicated across {@code PromptInputReader}, {@code JLineApprovalPrompt},
 * and approval / choice prompts.
 */
public final class TerminalKeys {

    private TerminalKeys() {}

    /**
     * Buffer for a non-CSI byte that followed a bare ESC.  Stored here so
     * callers that use {@link #readKey} don't lose the character.
     */
    private static final AtomicInteger PENDING = new AtomicInteger(-1);

    // ---- combined read -------------------------------------------------

    /**
     * Read a complete keystroke, decoding ESC sequences transparently.
     * The caller can switch on {@link KeyPress#key()} to dispatch.
     *
     * <p>When a bare ESC is followed by a non-CSI byte (e.g. Alt-letter in
     * some terminals), the follower is buffered and returned by the next
     * {@code readKey} call.
     */
    public static KeyPress readKey(NonBlockingReader reader) throws IOException {
        int pending = PENDING.get();
        if (pending >= 0) {
            PENDING.set(-1);
            return new KeyPress(decode(pending), pending);
        }
        int ch = reader.read();
        if (ch < 0) return new KeyPress(Key.EOF, -1);
        if (ch == 27) return readEscape(reader);
        return new KeyPress(decode(ch), ch);
    }

    /**
     * Like {@link #readKey} but with a timeout on the first byte so callers
     * can poll a shutdown flag.  Returns empty when the timeout elapses
     * without input; otherwise behaves identically to {@code readKey}.
     */
    public static java.util.Optional<KeyPress> pollKey(NonBlockingReader reader, long timeoutMs)
            throws IOException {
        int pending = PENDING.get();
        if (pending >= 0) {
            PENDING.set(-1);
            return java.util.Optional.of(new KeyPress(decode(pending), pending));
        }
        int ch = reader.read(timeoutMs);
        if (ch == -2) return java.util.Optional.empty();
        if (ch < 0) return java.util.Optional.of(new KeyPress(Key.EOF, -1));
        if (ch == 27) return java.util.Optional.of(readEscape(reader));
        return java.util.Optional.of(new KeyPress(decode(ch), ch));
    }

    // ---- individual key reads -----------------------------------------

    /** Read a single raw byte (blocking). */
    public static int read(NonBlockingReader reader) throws IOException {
        return reader.read();
    }

    /**
     * Decode an ESC-initiated sequence.  Returns a {@link KeyPress} so that
     * non-CSI followers can be buffered without loss.
     *
     * @return a KeyPress whose {@link KeyPress#key()} is the decoded key.
     */
    private static KeyPress readEscape(NonBlockingReader reader) throws IOException {
        int next = reader.read(100);
        if (next < 0) {
            return new KeyPress(Key.ESCAPE, -1);
        }
        if (next != '[' && next != 'O') {
            PENDING.set(next);
            return new KeyPress(Key.ESCAPE, -1);
        }
        String seq = readEscSequence(reader, next);
        if (seq.isEmpty()) {
            return new KeyPress(Key.ESCAPE, -1);
        }
        if ("[200~".equals(seq)) {
            return new KeyPress(Key.PASTE, -1, readBracketedPaste(reader));
        }
        if ("[201~".equals(seq)) {
            return new KeyPress(Key.PASTE_END, -1);
        }
        char finalByte = seq.charAt(seq.length() - 1);
        int modifier = csiModifier(seq);
        Key key = csiKey(finalByte, modifier, seq);
        return new KeyPress(key, 27);
    }

    private static String readEscSequence(NonBlockingReader reader, int introducer) throws IOException {
        StringBuilder seq = new StringBuilder();
        int code = introducer;
        seq.append((char) code);
        if (code != '[' && code != 'O') {
            return seq.toString();
        }
        do {
            code = reader.read(50);
            if (code < 0) break;
            seq.append((char) code);
        } while (!isFinalEscSequenceByte(code));
        return seq.toString();
    }

    private static boolean isFinalEscSequenceByte(int ch) {
        return ch >= 0x40 && ch <= 0x7e;
    }

    private static String readBracketedPaste(NonBlockingReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        String end = "\033[201~";
        while (true) {
            int ch = reader.read();
            if (ch < 0) break;
            text.append((char) ch);
            int n = text.length();
            if (n >= end.length()
                    && text.substring(n - end.length()).equals(end)) {
                text.setLength(n - end.length());
                break;
            }
        }
        String result = text.toString();
        result = result.replace("\r\n", "\n").replace('\r', '\n');
        return result;
    }

    /** Decode a single non-ESC byte. */
    public static Key decode(int ch) {
        return switch (ch) {
            case -1 -> Key.EOF;
            case '\r', '\n' -> Key.ENTER;
            case '\t' -> Key.TAB;
            case 1  -> Key.CTRL_A;
            case 2  -> Key.CTRL_B;
            case 3  -> Key.CTRL_C;
            case 4  -> Key.CTRL_D;
            case 5  -> Key.CTRL_E;
            case 11 -> Key.CTRL_K;
            case 15 -> Key.CTRL_O;
            case 21 -> Key.CTRL_U;
            case 23 -> Key.CTRL_W;
            case 27 -> Key.ESCAPE;
            case 127, '\b' -> Key.BACKSPACE;
            default -> Key.OTHER;
        };
    }

    // ---- types --------------------------------------------------------

    public enum Key {
        ENTER,
        TAB,
        ESCAPE,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END,
        PAGE_UP,
        PAGE_DOWN,
        BACKSPACE,
        DELETE,
        SHIFT_TAB,
        CTRL_A,
        CTRL_B,
        CTRL_C,
        CTRL_D,
        CTRL_E,
        CTRL_K,
        CTRL_O,
        CTRL_U,
        CTRL_W,
        CTRL_LEFT,
        CTRL_RIGHT,
        CTRL_DELETE,
        PASTE,
        PASTE_START,
        PASTE_END,
        EOF,
        OTHER
    }

    /**
     * A decoded keystroke: the logical {@link Key} plus the raw byte,
     * so callers that need printable characters can inspect {@link #ch()}.
     */
    public record KeyPress(Key key, int ch, String text) {
        public KeyPress(Key key, int ch) {
            this(key, ch, null);
        }

        public boolean isPrintable() {
            return ch >= 32 && ch != 127 && !Character.isISOControl(ch);
        }
    }

    /**
     * Whether a key is a vertical or horizontal navigation key.
     */
    public static boolean isNavigation(Key key) {
        return switch (key) {
            case UP, DOWN, LEFT, RIGHT -> true;
            default -> false;
        };
    }

    /**
     * Interpret the key as a navigation delta: UP/LEFT → -1, DOWN/RIGHT → +1.
     */
    public static int navigationDelta(Key key) {
        return switch (key) {
            case UP, LEFT -> -1;
            case DOWN, RIGHT -> 1;
            default -> 0;
        };
    }

    // -- extended CSI parsing -----------------------------------------------

    /**
     * Extracts the modifier value from a CSI parameter sequence.
     * E.g. {@code [1;5D} → 5 (Ctrl), {@code [1;3D} → 3 (Alt).
     */
    private static int csiModifier(String seq) {
        // seq is like "[1;5D" or "[D"
        int semi = seq.indexOf(';');
        if (semi < 0) return 0;
        int end = semi + 1;
        while (end < seq.length() && isDigit(seq.charAt(end))) end++;
        if (end == semi + 1) return 0;
        try {
            return Integer.parseInt(seq.substring(semi + 1, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Key csiKey(char finalByte, int modifier, String seq) {
        if (finalByte == '~' && modifier == 5 && seq.startsWith("[3")) {
            return Key.CTRL_DELETE;
        }
        if (modifier == 5) {
            return switch (finalByte) {
                case 'D' -> Key.CTRL_LEFT;
                case 'C' -> Key.CTRL_RIGHT;
                default -> Key.OTHER;
            };
        }
        if (modifier != 0) return Key.OTHER;

        return switch (finalByte) {
            case 'A' -> Key.UP;
            case 'B' -> Key.DOWN;
            case 'C' -> Key.RIGHT;
            case 'D' -> Key.LEFT;
            case 'H' -> Key.HOME;
            case 'F' -> Key.END;
            case 'Z' -> Key.SHIFT_TAB;
            case '~' -> csiTilde(seq);
            default -> Key.OTHER;
        };
    }

    private static Key csiTilde(String seq) {
        if ("[5~".equals(seq)) return Key.PAGE_UP;
        if ("[6~".equals(seq)) return Key.PAGE_DOWN;
        if ("[3~".equals(seq)) return Key.DELETE;
        return Key.OTHER;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
