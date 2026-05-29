package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

import java.util.Optional;

public final class ReadOnlyPermissionRule implements PermissionRule {

    public static final String SOURCE = "read_only";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (tool.isReadOnly()) {
            return Optional.of(PermissionDecision.allow(SOURCE));
        }
        return Optional.empty();
    }
}
