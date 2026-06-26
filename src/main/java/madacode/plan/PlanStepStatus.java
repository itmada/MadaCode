package madacode.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum PlanStepStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String wireName;

    PlanStepStatus(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static PlanStepStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "status is required and must be one of: pending, in_progress, completed");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (PlanStepStatus status : values()) {
            if (status.wireName.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "Invalid status: " + value + ". Must be: pending, in_progress, completed");
    }
}
