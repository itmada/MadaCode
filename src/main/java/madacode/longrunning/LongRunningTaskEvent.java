package madacode.longrunning;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Machine-readable event written to a long-running task's structured log.
 *
 * <p>The human-facing {@code progress.txt} remains the model's working
 * scratchpad. This event stream is for harness/runtime observability and
 * deterministic post-turn analysis.
 */
public record LongRunningTaskEvent(
        Instant timestamp,
        String type,
        String taskId,
        String sessionId,
        String stage,
        String action,
        Boolean success,
        String message,
        Map<String, String> details) {

    public LongRunningTaskEvent {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        type = requireNonBlank(type, "type");
        taskId = requireNonBlank(taskId, "taskId");
        sessionId = normalize(sessionId);
        stage = normalize(stage);
        action = normalize(action);
        message = normalize(message);
        details = Map.copyOf(Objects.requireNonNullElse(details, Map.of()));
    }

    public static LongRunningTaskEvent of(
            String type,
            String taskId,
            String sessionId,
            String stage,
            String action,
            Boolean success,
            String message,
            Map<String, String> details) {
        return new LongRunningTaskEvent(
                Instant.now(), type, taskId, sessionId, stage, action, success, message, details);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }
}
