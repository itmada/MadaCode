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

    public LongRunningTaskMetadata(
            String id,
            String title,
            String status,
            Instant createdAt,
            Instant updatedAt,
            String controlSessionId,
            String legacyStageOrPlanSummary) {
        this(
                id,
                title,
                normalizeLegacyStatus(status),
                normalizeLegacyReason(status, legacyStageOrPlanSummary),
                "RUNNING".equals(normalizeLegacyStatus(status)) ? createdAt : null,
                createdAt,
                updatedAt,
                controlSessionId,
                normalizeLegacyPlanSummary(legacyStageOrPlanSummary));
    }

    public String sessionId() {
        return controlSessionId;
    }

    public String stage() {
        return status;
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

    private static String normalizeLegacyStatus(String value) {
        return switch (requireNonBlank(value, "status").toUpperCase()) {
            case "PLANNING", "INITIALIZING", "INITIALIZED" -> "DRAFT";
            case "EXECUTING" -> "RUNNING";
            case "COMPLETED", "CANCELLED" -> "DONE";
            case "DRAFT", "RUNNING", "DONE" -> value.strip().toUpperCase();
            default -> throw new IllegalArgumentException("Unsupported task status: " + value);
        };
    }

    private static String normalizeLegacyReason(String status, String legacyStage) {
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
        if (legacyStage == null) {
            return null;
        }
        return switch (legacyStage.strip().toUpperCase()) {
            case "WAITING_FOR_APPROVAL" -> "awaiting_approval";
            case "INITIALIZING" -> "initializing";
            case "CANCELLED" -> "cancelled";
            case "COMPLETED" -> "task_completed";
            default -> null;
        };
    }

    private static String normalizeLegacyPlanSummary(String legacyStage) {
        if (legacyStage == null) {
            return null;
        }
        String normalized = legacyStage.strip();
        return switch (normalized.toUpperCase()) {
            case "PLANNING", "WAITING_FOR_APPROVAL", "INITIALIZING", "EXECUTING", "COMPLETED", "CANCELLED" -> null;
            default -> normalized.isBlank() ? null : normalized;
        };
    }
}
