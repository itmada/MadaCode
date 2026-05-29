package madacode.events;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;

public final class BootstrapFallbackPublisher implements AppEventPublisher {

    private final PrintStream err;

    public BootstrapFallbackPublisher() {
        this(System.err);
    }

    public BootstrapFallbackPublisher(PrintStream err) {
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public void publish(AppEvent event) {
        if (event instanceof DiagnosticEvent diagnostic) {
            switch (diagnostic.severity()) {
                case DEBUG, INFO -> {
                    return;
                }
                case WARN, ERROR -> {
                    // Bootstrap has no file sink yet; keep warnings and errors visible.
                }
            }
        }
        EventFallback.write(event, err);
    }

    @Override
    public long nextSequence() {
        return AppEvents.nextSequence();
    }

    @Override
    public void flush(Duration timeout) {
        err.flush();
    }

    @Override
    public void close() {
        err.flush();
    }
}
