package madacode.events;

import java.time.Instant;
import java.util.Objects;

public record FatalEvent(
        Instant timestamp,
        long sequence,
        EventContext context,
        String message,
        Throwable error,
        int exitCode) implements AppEvent {

    public FatalEvent {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        context = Objects.requireNonNull(context, "context");
        message = message == null ? "" : message;
    }

    public static FatalEvent create(EventContext context, String message, Throwable error, int exitCode) {
        return new FatalEvent(
                Instant.now(),
                AppEvents.publisher().nextSequence(),
                context,
                message,
                error,
                exitCode);
    }
}
