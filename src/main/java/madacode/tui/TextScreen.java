package madacode.tui;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Plain-stream {@link Screen} for tests, dumb terminals, and non-interactive
 * stdout. Only scrollback is written — so transcripts and golden tests stay
 * deterministic.
 */
public final class TextScreen implements Screen {

    private final PrintStream out;
    private final int width;
    private final int height;
    private int cursorHideDepth = 0;

    public TextScreen(PrintStream out) {
        this(out, 80, 24);
    }

    public TextScreen(PrintStream out, int width, int height) {
        this.out = Objects.requireNonNull(out, "out");
        this.width = Math.max(20, width);
        this.height = Math.max(5, height);
    }

    @Override
    public synchronized void scrollback(List<String> lines) {
        for (String line : lines) {
            out.println(line);
        }
        out.flush();
    }

    @Override
    public int width() { return width; }

    @Override
    public int height() { return height; }

    @Override
    public synchronized void flush() {
        out.flush();
    }

    @Override
    public synchronized void setCursorVisible(boolean visible) {
        if (visible) {
            if (cursorHideDepth > 0) cursorHideDepth--;
        } else {
            cursorHideDepth++;
        }
    }
}
