package madacode.events;

import java.time.Instant;
import java.util.Objects;

public record UserVisibleEvent(
        Instant timestamp,
        long sequence,
        EventContext context,
        Level level,
        String message,
        Throwable error) implements AppEvent {

    public enum Level { INFO, ERROR }

    public UserVisibleEvent {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        context = Objects.requireNonNull(context, "context");
        level = Objects.requireNonNull(level, "level");
        message = message == null ? "" : message;
    }

    public static UserVisibleEvent info(EventContext context, String message) {
        return create(context, Level.INFO, message, null);
    }

    public static UserVisibleEvent error(EventContext context, String message) {
        return create(context, Level.ERROR, message, null);
    }

    public static UserVisibleEvent error(EventContext context, String message, Throwable error) {
        return create(context, Level.ERROR, message, error);
    }

    static UserVisibleEvent create(
            EventContext context,
            Level level,
            String message,
            Throwable error) {
        return new UserVisibleEvent(
                Instant.now(),
                AppEvents.publisher().nextSequence(),
                context,
                level,
                message,
                error);
    }
}
