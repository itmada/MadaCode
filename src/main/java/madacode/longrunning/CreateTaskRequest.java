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

    public CreateTaskRequest(
            String id,
            String title,
            String status,
            String controlSessionId,
            String legacyStageOrPlanSummary) {
        this(
                id,
                title,
                status,
                normalizeLegacyReason(status, legacyStageOrPlanSummary),
                controlSessionId,
                normalizeLegacyPlanSummary(legacyStageOrPlanSummary));
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
            case "PLANNING", "INITIALIZING", "INITIALIZED" -> "DRAFT";
            case "EXECUTING" -> "RUNNING";
            case "COMPLETED", "CANCELLED" -> "DONE";
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

    private static String normalizeLegacyReason(String status, String stageOrPlanSummary) {
        String normalizedStatus = requireNonBlank(status, "status").toUpperCase();
        if ("CANCELLED".equals(normalizedStatus)) {
            return "cancelled";
        }
        if ("COMPLETED".equals(normalizedStatus)) {
            return "task_completed";
        }
        if ("INITIALIZING".equals(normalizedStatus) || "INITIALIZED".equals(normalizedStatus)) {
            return "initializing";
        }
        if (stageOrPlanSummary == null) {
            return null;
        }
        return switch (stageOrPlanSummary.strip().toUpperCase()) {
            case "WAITING_FOR_APPROVAL" -> "awaiting_approval";
            case "INITIALIZING" -> "initializing";
            case "CANCELLED" -> "cancelled";
            case "COMPLETED" -> "task_completed";
            default -> null;
        };
    }

    private static String normalizeLegacyPlanSummary(String stageOrPlanSummary) {
        if (stageOrPlanSummary == null) {
            return null;
        }
        String normalized = stageOrPlanSummary.strip();
        return switch (normalized.toUpperCase()) {
            case "PLANNING", "WAITING_FOR_APPROVAL", "INITIALIZING", "EXECUTING", "COMPLETED", "CANCELLED" -> null;
            default -> normalized.isBlank() ? null : normalized;
        };
    }
}
