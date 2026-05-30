package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

/**
 * Permission gate that controls which tool invocations are allowed.
 *
 * @see DefaultPermissionGate
 */
@FunctionalInterface
public interface PermissionGate {

    /**
     * Check whether a tool invocation is permitted.
     *
     * @param tool    the tool requesting execution
     * @param input   the structured input for the tool
     * @param context the current tool-use context (working directory, session)
     * @return the permission decision
     */
    PermissionDecision check(Tool<?> tool, ObjectNode input, ToolUseContext context);

    /**
     * A permissive gate that allows every tool invocation without prompting.
     * Useful for testing and for headless scenarios where no user interaction
     * is possible.
     *
     * <p><strong>Intentionally skips filesystem-scope policy.</strong>  The
     * default gate enforces directory scope and dangerous-target checks
     * via its rule chain; this permissive gate bypasses all of that,
     * matching the intended semantics of evaluation runs and unit tests.
     */
    static PermissionGate permissive() {
        return (tool, input, context) -> PermissionDecision.allow();
    }
}
