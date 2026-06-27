package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.util.Optional;

/**
 * Auto-allows progress-only session mutations.
 *
 * <p>{@code update_plan} changes ephemeral conversation UI state only: it does
 * not touch files, run commands, contact external services, or persist durable
 * task state. Keep it non-read-only so plan-mode and concurrency guards still
 * treat it as a state mutation, but bypass interactive permission prompts.
 */
public final class SessionProgressPermissionRule implements PermissionRule {

    public static final String SOURCE = "session_progress";

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.CAPABILITY;
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!ToolNames.UPDATE_PLAN.equals(tool.name())) {
            return Optional.empty();
        }
        return Optional.of(PermissionDecision.allow(layer(), SOURCE));
    }
}
