package madacode.render.turn;

import java.util.Objects;

record ToolActivityDescriptor(
        ToolActivityKind kind,
        ToolActivityGrouping grouping,
        String action,
        String target) {

    ToolActivityDescriptor {
        kind = Objects.requireNonNull(kind, "kind");
        grouping = Objects.requireNonNull(grouping, "grouping");
        action = Objects.requireNonNullElse(action, "");
        target = Objects.requireNonNullElse(target, "");
    }

    boolean groupableExploration() {
        return grouping == ToolActivityGrouping.GROUPABLE_EXPLORATION;
    }
}
