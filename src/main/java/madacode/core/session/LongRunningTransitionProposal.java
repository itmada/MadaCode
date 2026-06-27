package madacode.core.session;

import java.time.Instant;
import java.util.Objects;

public record LongRunningTransitionProposal(
        LongRunningStage sourceStage,
        LongRunningStage targetStage,
        String reason,
        String summary,
        String planDelta,
        Instant requestedAt,
        String requestedBy) {

    public LongRunningTransitionProposal {
        sourceStage = Objects.requireNonNull(sourceStage, "sourceStage");
        targetStage = Objects.requireNonNull(targetStage, "targetStage");
        reason = normalizeRequired(reason, "reason");
        summary = normalize(summary);
        planDelta = normalize(planDelta);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        requestedBy = normalize(requestedBy);
    }

    public static LongRunningTransitionProposal of(
            LongRunningStage sourceStage,
            LongRunningStage targetStage,
            String reason,
            String summary,
            String planDelta,
            String requestedBy) {
        return new LongRunningTransitionProposal(
                sourceStage, targetStage, reason, summary, planDelta,
                Instant.now(), requestedBy);
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
