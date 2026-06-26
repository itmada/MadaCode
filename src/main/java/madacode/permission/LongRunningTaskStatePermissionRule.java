package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Protects worker-owned long-running task source-of-truth files from generic mutation.
 *
 * <p>Worker sessions can read these files with normal read/search tools, but
 * updates must go through {@code longrun_task_update}, which delegates to
 * {@link madacode.longrunning.LongRunningTaskStore} and preserves invariants
 * such as "feature descriptions do not drift" and "passes only flips to true".
 *
 * <p>The controller session remains the main agent. Its ordinary file
 * operations are governed by the normal permission gate, so user-approved
 * cleanup such as deleting a stale task directory is not blocked here.
 */
public final class LongRunningTaskStatePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_task_state";
    private static final List<String> PROTECTED_STATE_FILENAMES = List.of(
            "task.json",
            "feature_list.json",
            "known_issues.json",
            "progress.txt",
            "events.jsonl",
            "checkpoint.json");

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
                && mutatesProtectedState(
                        input.path("command").asText(""),
                        workingDir,
                        activeTaskDirectory)) {
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

    private static boolean mutatesProtectedState(String command, Path workingDir, Path activeTaskDirectory) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!referencesActiveTask(normalized, workingDir, activeTaskDirectory)) {
            return false;
        }
        return normalized.contains(">")
                || normalized.contains("tee ")
                || normalized.contains("mv ")
                || normalized.contains("cp ")
                || normalized.contains("rm ")
                || normalized.contains("sed -i")
                || normalized.contains("perl -pi")
                || normalized.contains("touch ")
                || normalized.contains("truncate ")
                || normalized.contains("install ")
                || normalized.contains("echo ")
                || normalized.contains("printf ");
    }

    private static boolean referencesActiveTask(String command, Path workingDir, Path activeTaskDirectory) {
        String absoluteTaskDir = activeTaskDirectory.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (command.contains(absoluteTaskDir)) {
            return true;
        }
        if (activeTaskDirectory.startsWith(workingDir.toAbsolutePath().normalize())) {
            String relativeTaskDir = workingDir.toAbsolutePath()
                    .normalize()
                    .relativize(activeTaskDirectory)
                    .toString()
                    .replace('\\', '/')
                    .toLowerCase(Locale.ROOT);
            if (!relativeTaskDir.isBlank() && command.contains(relativeTaskDir)) {
                return true;
            }
        }
        return PROTECTED_STATE_FILENAMES.stream().anyMatch(command::contains);
    }

    private static PermissionDecision deny() {
        return PermissionDecision.deny(
                "Long-running task state files are runtime-owned and must be updated with long-running task-store tools.",
                SOURCE);
    }
}
