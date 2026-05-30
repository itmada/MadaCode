package madacode.tui.inline;

import madacode.tui.Screen;
import madacode.tui.Suspendable;
import madacode.tui.TerminalKeys;
import madacode.tui.TerminalText;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Inline single-line text prompt rendered through {@link Screen#setLiveModal}.
 */
public final class InlineTextPrompt {

    private static final String CURSOR = "█";

    private final Screen screen;
    private final Terminal terminal;
    private final Suspendable readerLock;
    private final Consumer<String> onInterrupt;

    public InlineTextPrompt(Screen screen, Terminal terminal, Suspendable readerLock) {
        this(screen, terminal, readerLock, null);
    }

    public InlineTextPrompt(Screen screen, Terminal terminal, Suspendable readerLock,
                            Consumer<String> onInterrupt) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.readerLock = readerLock;
        this.onInterrupt = onInterrupt;
    }

    public Optional<String> read(String prompt) throws IOException {
        LineEditor editor = new LineEditor();
        Attributes previous = terminal.enterRawMode();
        screen.setCursorVisible(false);
        try {
            while (true) {
                screen.setLiveModal(renderLines(prompt, editor, screen.width()));

                TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());
                switch (key.key()) {
                    case ENTER -> {
                        screen.clearLiveModal();
                        return Optional.of(editor.text());
                    }
                    case ESCAPE -> {
                        screen.clearLiveModal();
                        fireInterrupt("esc");
                        return Optional.empty();
                    }
                    case CTRL_C -> {
                        screen.clearLiveModal();
                        fireInterrupt("sigint");
                        return Optional.empty();
                    }
                    case CTRL_D, EOF -> {
                        screen.clearLiveModal();
                        fireInterrupt("eof");
                        return Optional.empty();
                    }
                    case BACKSPACE -> editor.backspace();
                    case DELETE -> editor.delete();
                    case LEFT -> editor.moveLeft();
                    case RIGHT -> editor.moveRight();
                    case HOME -> editor.home();
                    case END -> editor.end();
                    case PASTE -> editor.insert(sanitize(key.text()));
                    default -> {
                        if (key.isPrintable()) {
                            editor.insert(Character.toString((char) key.ch()));
                        }
                    }
                }
            }
        } finally {
            try {
                screen.clearLiveModal();
            } finally {
                try {
                    screen.setCursorVisible(true);
                } finally {
                    terminal.setAttributes(previous);
                }
            }
        }
    }

    static List<String> renderLines(String prompt, LineEditor editor, int width) {
        int safeWidth = Math.max(1, width);
        List<String> lines = new ArrayList<>();
        String text = editor.text();
        int cursor = Math.max(0, Math.min(editor.cursor(), text.length()));
        String inputLine = text.substring(0, cursor) + CURSOR + text.substring(cursor);

        if (prompt != null && !prompt.isEmpty()) {
            for (String line : prompt.split("\\n", -1)) {
                lines.add(TerminalText.fitEnd(line, safeWidth));
            }
        }
        lines.add(fitEndPreservingSpaces(inputLine, safeWidth));
        return lines;
    }

    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r' || cp == '\n' || cp == '\t') {
                cleaned.append(' ');
            } else if (!Character.isISOControl(cp)) {
                cleaned.appendCodePoint(cp);
            }
        }
        return cleaned.toString();
    }

    private static String fitEndPreservingSpaces(String value, int maxColumns) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        if (TerminalText.displayWidth(clean) <= maxColumns) {
            return clean;
        }
        if (maxColumns <= 0) {
            return "";
        }
        if (maxColumns == 1) {
            return "…";
        }
        return takeFromStart(clean, maxColumns - 1) + "…";
    }

    private static String takeFromStart(String value, int columns) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length(); ) {
            int end = TerminalText.clusterEnd(value, i);
            String cluster = value.substring(i, end);
            int width = Math.max(0, TerminalText.displayWidth(cluster));
            if (used + width > columns) {
                break;
            }
            result.append(cluster);
            used += width;
            i = end;
        }
        return result.toString();
    }

    private void fireInterrupt(String reason) {
        if (onInterrupt != null) onInterrupt.accept(reason);
    }
}
