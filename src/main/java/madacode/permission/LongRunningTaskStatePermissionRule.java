package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Protects active long-running task source-of-truth files from generic access.
 *
 * <p>The model-facing environment tools are the read/write boundary. That keeps
 * inspection and mutation behind {@link madacode.longrunning.LongRunningTaskStore},
 * which preserves invariants such as "feature descriptions do not drift" and
 * "passes only flips to true".
 */
public final class LongRunningTaskStatePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_task_state";

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.SAFETY;
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        Path activeTaskDirectory = activeTaskDirectory(context);
        if (activeTaskDirectory == null || isOfficialLongRunningTaskStoreTool(tool.name())) {
            return Optional.empty();
        }

        Path workingDir = context.workingDirectory();
        if (tool.isFileEdit()) {
            List<String> targets = tool.permissionTargets(input);
            for (String target : targets) {
                if (FilesystemScope.isProtectedLongRunningTaskStateTarget(target, workingDir, activeTaskDirectory)) {
                    return Optional.of(deny());
                }
            }
        }

        if ("bash".equals(tool.name())
                && FilesystemScope.isProtectedLongRunningTaskStateShellAccess(
                        input.path("command").asText(""), workingDir, activeTaskDirectory)) {
            return Optional.of(deny());
        }

        return Optional.empty();
    }

    private static Path activeTaskDirectory(ToolUseContext context) {
        if (!context.session().isLongRunningModeActive()) {
            return null;
        }
        String taskDirectory = context.session().longRunningTaskDirectory();
        if (taskDirectory == null || taskDirectory.isBlank()) {
            return null;
        }
        return Path.of(taskDirectory).toAbsolutePath().normalize();
    }

    private static boolean isOfficialLongRunningTaskStoreTool(String toolName) {
        return ToolNames.LONGRUN_ENVIRONMENT_READ.equals(toolName)
                || ToolNames.LONGRUN_ENVIRONMENT_UPDATE.equals(toolName)
                || ToolNames.LONGRUN_STATE_TRANSITION.equals(toolName)
                || ToolNames.WORKER_REPORT.equals(toolName);
    }

    private static PermissionDecision deny() {
        return PermissionDecision.deny(
                "Long-running task state files are runtime-owned. Use longrun_environment_read to inspect them and longrun_environment_update to change them; do not access .mada/long-running with generic file or bash tools.",
                PermissionLayer.SAFETY,
                SOURCE);
    }
}
