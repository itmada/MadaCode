package madacode.events;

import java.time.Instant;
import java.util.Objects;

public record AuditEvent(
        Instant timestamp,
        long sequence,
        EventContext context,
        String tool,
        boolean allowed,
        String reason,
        String permissionSource,
        long waitMs,
        String inputPreview) implements AppEvent {

    public AuditEvent {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        context = Objects.requireNonNull(context, "context");
        tool = tool == null ? "" : tool;
        reason = reason == null ? "" : reason;
        permissionSource = permissionSource == null ? "" : permissionSource;
        inputPreview = inputPreview == null ? "" : inputPreview;
    }

    public static AuditEvent permissionDecision(
            EventContext context,
            String tool,
            boolean allowed,
            String reason,
            String permissionSource,
            long waitMs,
            String inputPreview) {
        return new AuditEvent(
                Instant.now(),
                AppEvents.publisher().nextSequence(),
                context,
                tool,
                allowed,
                reason,
                permissionSource,
                waitMs,
                inputPreview);
    }
}
