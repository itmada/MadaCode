package madacode.plan;

import java.util.List;
import java.util.Objects;

public record CurrentPlan(List<PlanStep> steps) {

    public static final CurrentPlan EMPTY = new CurrentPlan(List.of());

    public CurrentPlan {
        steps = List.copyOf(Objects.requireNonNullElse(steps, List.of()));
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
