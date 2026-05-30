package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Auto-allows any tool when the session is in {@link PermissionMode#BYPASS},
 * with two exceptions:
 * <ol>
 *   <li>File-edit tools targeting dangerous paths (shell config,
 *       {@code .git/} hooks, etc.) fall through to the interactive prompt
 *       even in BYPASS mode.</li>
 *   <li>Bash commands that write to dangerous files (e.g.
 *       {@code echo >> ~/.bashrc}) are caught upstream by
 *       {@link BashSafetyPermissionRule}.</li>
 * </ol>
 * Dangerous writes are too impactful to auto-approve without explicit user
 * consent, regardless of permission mode.
 *
 * <p>IMPORTANT: This rule MUST run AFTER {@link BashSafetyPermissionRule}
 * in {@link DefaultPermissionGate}'s rule list — BYPASS only suppresses
 * prompting, it does not override structural safety denials like
 * {@code rm -rf /} or {@code curl | bash}.
 */
public final class BypassPermissionRule implements PermissionRule {

    public static final String SOURCE = "bypass_mode";

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (context.session().permissionMode() != PermissionMode.BYPASS) {
            return Optional.empty();
        }

        if (tool.isFileEdit()) {
            List<String> targets = tool.permissionTargets(input);
            if (targets.isEmpty()) {
                return Optional.empty();
            }
            Path workingDir = context.workingDirectory();
            for (String target : targets) {
                if (FilesystemScope.isDangerousEditTarget(target, workingDir)) {
                    return Optional.empty();
                }
            }
        }

        return Optional.of(PermissionDecision.allow(SOURCE));
    }
}
