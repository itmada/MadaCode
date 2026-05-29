package madacode.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PlanItem(
        String id,
        String title,
        String description,
        PlanStatus status,
        List<String> blockedBy,
        Instant createdAt,
        Instant updatedAt,
        String activeForm) {

    public static PlanItem create(String id, String title, String description, List<String> blockedBy) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        var now = Instant.now();
        return new PlanItem(
                id, title,
                description != null ? description : "",
                PlanStatus.PENDING,
                blockedBy != null ? List.copyOf(blockedBy) : List.of(),
                now, now, "");
    }

    public PlanItem withActiveForm(String form) {
        return new PlanItem(id, title, description, status, blockedBy, createdAt, updatedAt,
                form != null ? form : "");
    }

    public PlanItem transitionTo(PlanStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalArgumentException(
                    "Cannot transition plan item " + id + " from " + status + " to " + target);
        }
        return new PlanItem(id, title, description, target, blockedBy, createdAt, Instant.now(), activeForm);
    }
}
