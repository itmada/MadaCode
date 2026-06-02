package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Protects long-running task source-of-truth files from generic mutation.
 *
 * <p>The model can read these files with normal read/search tools, but updates
 * must go through {@code longrun_task_update}, which delegates to
 * {@link madacode.longrunning.LongRunningTaskStore} and preserves invariants
 * such as "feature descriptions do not drift" and "passes only flips to true".
 */
public final class LongRunningTaskStatePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_task_state";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        Path workingDir = context.workingDirectory();
        if (tool.isFileEdit()) {
            List<String> targets = tool.permissionTargets(input);
            for (String target : targets) {
                if (FilesystemScope.isProtectedLongRunningTaskStateTarget(target, workingDir)) {
                    return Optional.of(deny());
                }
            }
        }

        if ("bash".equals(tool.name())
                && FilesystemScope.isProtectedLongRunningTaskStateShellAccess(
                        input.path("command").asText(""), workingDir)) {
            return Optional.of(deny());
        }

        return Optional.empty();
    }

    private static PermissionDecision deny() {
        return PermissionDecision.deny(
                "Long-running task state files must be updated with longrun_task_update.",
                SOURCE);
    }
}
