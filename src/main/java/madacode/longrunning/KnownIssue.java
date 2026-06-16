package madacode.longrunning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A known issue (bug / defect) tracked alongside the feature list.
 *
 * <p>Status lifecycle: {@code open} (needs fixing, blocks issue-first
 * scheduling) ↔ {@code blocked} ↔ {@code deferred} (parked after repeated
 * failed fix attempts, no longer blocks subsequent work) → {@code resolved}.
 *
 * <p>{@code attempts} counts consecutive failed fix attempts. When it reaches
 * the escape-valve threshold the issue is auto-deferred (ordinary severity) or
 * escalated to the user (blocker severity) so a single hard issue can never
 * stall the whole run.
 */
public record KnownIssue(
        String id,
        String description,
        String severity,
        String status,
        String discoveredIn,
        List<String> verificationSteps,
        int attempts,
        Instant createdAt,
        Instant resolvedAt) {

    /** Convenience constructor for issues with no recorded fix attempts yet. */
    public KnownIssue(
            String id,
            String description,
            String severity,
            String status,
            String discoveredIn,
            List<String> verificationSteps,
            Instant createdAt,
            Instant resolvedAt) {
        this(id, description, severity, status, discoveredIn, verificationSteps, 0, createdAt, resolvedAt);
    }

    public KnownIssue {
        id = requireNonBlank(id, "id");
        description = requireNonBlank(description, "description");
        severity = requireNonBlank(severity, "severity");
        status = requireNonBlank(status, "status");
        discoveredIn = requireNonBlank(discoveredIn, "discoveredIn");
        verificationSteps = List.copyOf(Objects.requireNonNullElse(verificationSteps, List.of()));
        attempts = Math.max(0, attempts);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** True while this issue still demands attention before new feature work. */
    public boolean isActive() {
        return "open".equals(status) || "blocked".equals(status);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
