package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class EditModePermissionRule implements PermissionRule {

    public static final String SOURCE = "edit_mode";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (context.session().permissionMode() != PermissionMode.EDIT) {
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
