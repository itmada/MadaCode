package madacode.longrunning;

import java.time.Instant;
import java.util.Objects;

public record LongRunningTaskMetadata(
        String id,
        String title,
        String status,
        String reason,
        Instant executionStarted,
        Instant createdAt,
        Instant updatedAt,
        String controlSessionId,
        String planSummary) {

    public LongRunningTaskMetadata {
        id = requireNonBlank(id, "id");
        title = requireNonBlank(title, "title");
        status = requireStatus(status);
        reason = normalizeOptional(reason);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        controlSessionId = normalizeOptional(controlSessionId);
        planSummary = normalizeOptional(planSummary);
    }

    public String sessionId() {
        return controlSessionId;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String requireStatus(String value) {
        String normalized = requireNonBlank(value, "status").toUpperCase();
        return switch (normalized) {
            case "DRAFT", "RUNNING", "INTERRUPT", "COMPLETED", "CANCELLED", "FAILED" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported task status: " + value);
        };
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

}
