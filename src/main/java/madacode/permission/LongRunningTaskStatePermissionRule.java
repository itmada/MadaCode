package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Protects active long-running task source-of-truth files from generic mutation.
 *
 * <p>Read/search tools may inspect these files, but updates must go through
 * the dedicated task-store tools. That keeps state changes behind
 * {@link madacode.longrunning.LongRunningTaskStore}, which preserves
 * invariants such as "feature descriptions do not drift" and "passes only
 * flips to true".
 */
public final class LongRunningTaskStatePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_task_state";

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
        return "longrun_plan_update".equals(toolName)
                || "longrun_task_update".equals(toolName)
                || "longrun_state_transition_request".equals(toolName)
                || "worker_report".equals(toolName);
    }

    private static PermissionDecision deny() {
        return PermissionDecision.deny(
                "Long-running task state files are runtime-owned and must be updated with long-running task-store tools.",
                SOURCE);
    }
}
