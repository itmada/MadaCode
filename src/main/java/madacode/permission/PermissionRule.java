package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.util.Optional;

public interface PermissionRule {

    default PermissionLayer layer() {
        return PermissionLayer.FALLBACK;
    }

    Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context);
}
