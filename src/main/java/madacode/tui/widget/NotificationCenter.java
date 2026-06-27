package madacode.tui.widget;

import madacode.tui.Screen;
import madacode.tui.theme.Tk;
import madacode.tui.theme.Token;

import java.util.Objects;

/**
 * Inline notification printer: writes transient notices directly into
 * scrollback instead of a bottom-pinned region.
 */
public final class NotificationCenter {

    public enum Level { INFO, WARN, ERROR }

    private final Screen screen;

    public NotificationCenter(Screen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    public void info(String message) {
        publish(Level.INFO, message);
    }

    public void warn(String message) {
        publish(Level.WARN, message);
    }

    public void error(String message) {
        publish(Level.ERROR, message);
    }

    public void publish(Level level, String message) {
        if (message == null || message.isBlank()) return;
        String prefix = switch (level) {
            case INFO -> Tk.apply(Token.TAG_INFO, "▏info");
            case WARN -> Tk.apply(Token.TAG_WARN, "▏warn");
            case ERROR -> Tk.apply(Token.TAG_ERROR, "▏error");
        };
        screen.commitBlock(prefix + "  " + message.strip());
    }

    public void clear() {
        // No drawer to clear — messages are already in scrollback.
    }
}
