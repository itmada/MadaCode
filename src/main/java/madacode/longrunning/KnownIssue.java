package madacode.longrunning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record KnownIssue(
        String id,
        String description,
        String severity,
        String status,
        String discoveredIn,
        List<String> verificationSteps,
        Instant createdAt,
        Instant resolvedAt) {

    public KnownIssue {
        id = requireNonBlank(id, "id");
        description = requireNonBlank(description, "description");
        severity = requireNonBlank(severity, "severity");
        status = requireNonBlank(status, "status");
        discoveredIn = requireNonBlank(discoveredIn, "discoveredIn");
        verificationSteps = List.copyOf(Objects.requireNonNullElse(verificationSteps, List.of()));
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
