package madacode.events;

import java.time.Instant;
import java.util.Objects;

public record DiagnosticEvent(
        Instant timestamp,
        long sequence,
        EventContext context,
        Severity severity,
        String message,
        Throwable error) implements AppEvent {

    public DiagnosticEvent {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        context = Objects.requireNonNull(context, "context");
        severity = Objects.requireNonNull(severity, "severity");
        message = message == null ? "" : message;
    }

    public static DiagnosticEvent debug(EventContext context, String message) {
        return create(context, Severity.DEBUG, message, null);
    }

    public static DiagnosticEvent info(EventContext context, String message) {
        return create(context, Severity.INFO, message, null);
    }

    public static DiagnosticEvent warn(EventContext context, String message) {
        return create(context, Severity.WARN, message, null);
    }

    public static DiagnosticEvent warn(EventContext context, String message, Throwable error) {
        return create(context, Severity.WARN, message, error);
    }

    public static DiagnosticEvent error(EventContext context, String message, Throwable error) {
        return create(context, Severity.ERROR, message, error);
    }

    static DiagnosticEvent create(
            EventContext context,
            Severity severity,
            String message,
            Throwable error) {
        return new DiagnosticEvent(
                Instant.now(),
                AppEvents.nextSequence(),
                context,
                severity,
                message,
                error);
    }
}
