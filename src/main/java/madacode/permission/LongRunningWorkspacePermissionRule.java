package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class LongRunningWorkspacePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_workspace";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!applies(context)) {
            return Optional.empty();
        }

        if ("bash".equals(tool.name())) {
            return Optional.of(PermissionDecision.allow(SOURCE));
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
        }

        return Optional.of(PermissionDecision.allow(SOURCE));
    }

    private static boolean applies(ToolUseContext context) {
        return context.session().isLongRunningWorkerSession()
                && context.session().permissionMode() == PermissionMode.LONG_RUNNING_WORKSPACE;
    }
}
