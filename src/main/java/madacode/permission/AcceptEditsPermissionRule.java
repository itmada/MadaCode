package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Auto-allows file-edit tools in {@link PermissionMode#ACCEPT_EDITS} mode
 * when all targets are inside the working directory and none are dangerous.
 *
 * <p>Dangerous targets (shell config files, {@code .git} directories, etc.)
 * are never auto-allowed even inside the working directory — they fall
 * through to the interactive prompt so the user can decide.
 *
 * <p>Sub-agents that inherit the parent's gate are automatically subject
 * to the same restrictions.
 */
public final class AcceptEditsPermissionRule implements PermissionRule {

    public static final String SOURCE = "accept_edits";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (context.session().permissionMode() != PermissionMode.ACCEPT_EDITS) {
            return Optional.empty();
        }
        if (!tool.isFileEdit()) {
            return Optional.empty();
        }

        List<String> targets = tool.permissionTargets(input);
        if (targets.isEmpty()) {
            return Optional.empty();
        }

        Path workingDir = context.workingDirectory();

        for (String target : targets) {
            if (!FilesystemScope.withinRoots(target, workingDir, List.of())) {
                return Optional.empty();
            }
            if (FilesystemScope.isDangerousEditTarget(target, workingDir)) {
                return Optional.empty();
            }
        }

        return Optional.of(PermissionDecision.allow(SOURCE));
    }
}
