package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

import java.util.Optional;

/**
 * Auto-allows tools that declare {@code isFileEdit() = true} when the
 * session is in {@link PermissionMode#ACCEPT_EDITS}. Other non-readonly
 * tools (bash, web fetch, etc.) still fall through to user approval.
 *
 * <p>Tools self-classify via {@link Tool#isFileEdit()} — this rule
 * carries no per-tool knowledge.
 */
public final class AcceptEditsPermissionRule implements PermissionRule {

    public static final String SOURCE = "accept_edits";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (context.session().permissionMode() == PermissionMode.ACCEPT_EDITS
                && tool.isFileEdit()) {
            return Optional.of(PermissionDecision.allow(SOURCE));
        }
        return Optional.empty();
    }
}
