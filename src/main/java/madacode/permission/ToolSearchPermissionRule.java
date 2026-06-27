package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolSearchTool;

import java.util.Optional;

/**
 * Auto-allows tool discovery. Tool search mutates session-visible tool state,
 * but it does not touch files, run commands, or contact external services.
 */
public final class ToolSearchPermissionRule implements PermissionRule {

    public static final String SOURCE = "tool_search";

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.CAPABILITY;
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!ToolSearchTool.NAME.equals(tool.name())) {
            return Optional.empty();
        }
        return Optional.of(PermissionDecision.allow(layer(), SOURCE));
    }
}
