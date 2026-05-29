package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

import java.util.Optional;

/**
 * Auto-allows any tool when the session is in {@link PermissionMode#BYPASS}.
 *
 * <p>IMPORTANT: This rule MUST run AFTER {@link BashSafetyPermissionRule}
 * in {@link DefaultPermissionGate}'s rule list — BYPASS only suppresses
 * prompting, it does not override structural safety denials like
 * {@code rm -rf /} or {@code curl | bash}.
 */
public final class BypassPermissionRule implements PermissionRule {

    public static final String SOURCE = "bypass_mode";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (context.session().permissionMode() == PermissionMode.BYPASS) {
            return Optional.of(PermissionDecision.allow(SOURCE));
        }
        return Optional.empty();
    }
}
