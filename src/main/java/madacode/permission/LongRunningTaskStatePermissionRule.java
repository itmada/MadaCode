package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

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
        return ToolNames.LONGRUN_PLAN_UPDATE.equals(toolName)
                || ToolNames.LONGRUN_TASK_SUMMARY_UPDATE.equals(toolName)
                || ToolNames.LONGRUN_FEATURE_LIST_REPLACE.equals(toolName)
                || ToolNames.LONGRUN_KNOWN_ISSUES_REPLACE.equals(toolName)
                || ToolNames.LONGRUN_PROGRESS_APPEND.equals(toolName)
                || ToolNames.LONGRUN_TASK_UPDATE.equals(toolName)
                || ToolNames.LONGRUN_STATE_TRANSITION_REQUEST.equals(toolName)
                || ToolNames.WORKER_REPORT.equals(toolName);
    }

    private static PermissionDecision deny() {
        return PermissionDecision.deny(
                "Long-running task state files are runtime-owned and must be updated with long-running task-store tools.",
                SOURCE);
    }
}
