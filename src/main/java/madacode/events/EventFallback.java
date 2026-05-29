package madacode.events;

import java.io.PrintStream;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class EventFallback {

    private static final long MIN_INTERVAL_MILLIS = 60_000;
    private static final AtomicLong lastFailureAt = new AtomicLong();

    private EventFallback() {
    }

    public static void write(AppEvent event, PrintStream err) {
        if (event instanceof FatalEvent fatal) {
            err.println("[FATAL] " + fatal.message());
            if (fatal.error() != null) {
                fatal.error().printStackTrace(err);
            }
            err.flush();
            return;
        }
        String message = switch (event) {
            case UserVisibleEvent u -> u.message();
            case DiagnosticEvent d -> "[" + d.severity() + "] [" + d.context().source() + "] " + d.message();
            case AuditEvent a -> "[AUDIT] tool=" + a.tool() + " allowed=" + a.allowed()
                    + " reason=" + a.reason();
            case FatalEvent f -> f.message();
        };
        if (message != null && !message.isBlank()) {
            err.println(message);
            err.flush();
        }
    }

    public static void writeFailure(String message, Throwable error, PrintStream err) {
        long now = System.currentTimeMillis();
        long previous = lastFailureAt.get();
        if (now - previous < MIN_INTERVAL_MILLIS || !lastFailureAt.compareAndSet(previous, now)) {
            return;
        }
        err.println("[mada] logging fallback " + Instant.now() + ": " + message);
        if (error != null) {
            err.println(error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        }
        err.flush();
    }
}
