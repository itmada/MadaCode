package madacode.longrunning;

import java.time.Instant;
import java.util.Objects;

public record LongRunningTaskMetadata(
        String id,
        String title,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String sessionId,
        String stage) {

    public LongRunningTaskMetadata {
        id = requireNonBlank(id, "id");
        title = requireNonBlank(title, "title");
        status = requireNonBlank(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        sessionId = requireNonBlank(sessionId, "sessionId");
        stage = requireNonBlank(stage, "stage");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
