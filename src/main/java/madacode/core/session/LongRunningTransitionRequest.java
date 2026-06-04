package madacode.core.session;

import java.time.Instant;
import java.util.Objects;

public record LongRunningTransitionRequest(
        LongRunningStage sourceStage,
        LongRunningStage targetStage,
        String reason,
        String summary,
        String planDelta,
        Instant requestedAt,
        String requestedBy,
        boolean userConfirmationRequired) {

    public LongRunningTransitionRequest {
        sourceStage = Objects.requireNonNull(sourceStage, "sourceStage");
        targetStage = Objects.requireNonNull(targetStage, "targetStage");
        reason = normalizeRequired(reason, "reason");
        summary = normalize(summary);
        planDelta = normalize(planDelta);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        requestedBy = normalize(requestedBy);
    }

    public static LongRunningTransitionRequest of(
            LongRunningStage sourceStage,
            LongRunningStage targetStage,
            String reason,
            String summary,
            String planDelta,
            String requestedBy) {
        return new LongRunningTransitionRequest(
                sourceStage, targetStage, reason, summary, planDelta,
                Instant.now(), requestedBy, true);
    }

    public boolean isCancellation() {
        return targetStage == LongRunningStage.DONE
                && ("user_requested_cancel".equals(reason) || "failure".equals(reason));
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
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
