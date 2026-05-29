package madacode.plan;

public record TodoItem(String content, String status) {

    private static final java.util.Set<String> VALID_STATUSES =
            java.util.Set.of("pending", "in_progress", "completed");

    public TodoItem {
        if (status == null || !VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException(
                    "Invalid todo status: " + status + ". Must be one of: pending, in_progress, completed");
        }
    }
}
