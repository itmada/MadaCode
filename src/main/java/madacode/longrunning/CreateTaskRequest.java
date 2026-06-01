package madacode.longrunning;

import java.util.Objects;

public record CreateTaskRequest(
        String id,
        String title,
        String status,
        String sessionId,
        String stage) {

    public CreateTaskRequest {
        id = requireNonBlank(id, "id");
        title = requireNonBlank(title, "title");
        status = requireNonBlank(status, "status");
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
