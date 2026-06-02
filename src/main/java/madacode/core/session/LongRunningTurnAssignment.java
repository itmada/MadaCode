package madacode.core.session;

import java.util.List;
import java.util.Objects;

/**
 * Harness-selected work target for a single long-running execution turn.
 */
public record LongRunningTurnAssignment(
        Kind kind,
        String id,
        String description,
        String reason,
        List<String> verificationSteps) {

    public LongRunningTurnAssignment {
        kind = Objects.requireNonNull(kind, "kind");
        id = normalize(id);
        description = normalize(description);
        reason = normalize(reason);
        verificationSteps = List.copyOf(Objects.requireNonNullElse(verificationSteps, List.of()));
    }

    public enum Kind {
        SEED_FEATURE_LIST,
        ISSUE,
        FEATURE,
        COMPLETE_TASK,
        BLOCKED
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }
}
