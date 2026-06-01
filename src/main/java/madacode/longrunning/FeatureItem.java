package madacode.longrunning;

import java.util.List;
import java.util.Objects;

public record FeatureItem(
        String id,
        String category,
        String priority,
        String description,
        List<String> dependsOn,
        List<String> verificationSteps,
        boolean passes) {

    public FeatureItem {
        id = requireNonBlank(id, "id");
        category = requireNonBlank(category, "category");
        priority = requireNonBlank(priority, "priority");
        description = requireNonBlank(description, "description");
        dependsOn = List.copyOf(Objects.requireNonNullElse(dependsOn, List.of()));
        verificationSteps = List.copyOf(Objects.requireNonNullElse(verificationSteps, List.of()));
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
