package madacode.longrunning;

import java.util.Objects;

public record CreateTaskRequest(
        String id,
        String title,
        String status,
        String reason,
        String controlSessionId,
        String planSummary) {

    public CreateTaskRequest {
        id = requireNonBlank(id, "id");
        title = requireNonBlank(title, "title");
        status = normalizeStatus(status);
        reason = normalizeOptional(reason);
        controlSessionId = normalizeOptional(controlSessionId);
        planSummary = normalizeOptional(planSummary);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeStatus(String value) {
        String normalized = requireNonBlank(value, "status").toUpperCase();
        return switch (normalized) {
            case "DRAFT", "RUNNING", "DONE" -> normalized;
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
