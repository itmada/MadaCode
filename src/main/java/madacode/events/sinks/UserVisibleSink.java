package madacode.events.sinks;

import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;
import madacode.events.Severity;
import madacode.events.Sink;
import madacode.events.UserVisibleEvent;
import madacode.tui.Screen;
import madacode.tui.theme.Tk;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UserVisibleSink implements Sink<UserVisibleEvent> {

    private final Screen screen;
    private final PrintStream stderrFallback;
    private final Supplier<String> foregroundSessionId;
    private final Consumer<DiagnosticEvent> hiddenHandler;

    public UserVisibleSink(
            Screen screen,
            PrintStream stderrFallback,
            Supplier<String> foregroundSessionId,
            Consumer<DiagnosticEvent> hiddenHandler) {
        this.screen = screen;
        this.stderrFallback = Objects.requireNonNull(stderrFallback, "stderrFallback");
        this.foregroundSessionId = foregroundSessionId;
        this.hiddenHandler = hiddenHandler == null ? event -> {} : hiddenHandler;
    }

    @Override
    public void accept(UserVisibleEvent event) {
        if (!isForeground(event.context())) {
            hiddenHandler.accept(new DiagnosticEvent(
                    Instant.now(),
                    event.sequence(),
                    event.context(),
                    Severity.INFO,
                    "hidden user-visible event: " + event.message(),
                    event.error()));
            return;
        }

        String line = format(event);
        if (screen != null) {
            screen.scrollback(line);
        } else {
            stderrFallback.println(line);
            stderrFallback.flush();
        }
    }

    private boolean isForeground(EventContext context) {
        if (context.sessionId() == null || foregroundSessionId == null) {
            return true;
        }
        String foreground = foregroundSessionId.get();
        return foreground == null || foreground.equals(context.sessionId());
    }

    private static String format(UserVisibleEvent event) {
        String message = event.message() == null ? "" : event.message().strip();
        if (event.error() != null && (message.isBlank())) {
            message = event.error().getMessage();
        }
        if (message == null || message.isBlank()) {
            message = "Unknown error";
        }
        if (event.level() == UserVisibleEvent.Level.INFO) {
            return message;
        }
        return Tk.errorTag("error") + " " + message;
    }
}
