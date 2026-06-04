package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LongRunningWorkspacePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_workspace";
    private static final Pattern ABSOLUTE_OR_HOME_TOKEN =
            Pattern.compile("(?<![\\w.-])(/[^\\s'\";|&<>`$]+|~/?[^\\s'\";|&<>`]*)");

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!applies(context)) {
            return Optional.empty();
        }

        if ("bash".equals(tool.name())) {
            if (!bashStaysInWorkspace(input.path("command").asText(""), context.workingDirectory())) {
                return Optional.of(PermissionDecision.deny(
                        "Long-running worker bash commands must stay inside the workspace.",
                        SOURCE));
            }
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

    private static boolean bashStaysInWorkspace(String command, Path workingDir) {
        if (command == null || command.isBlank()) {
            return true;
        }
        Matcher matcher = ABSOLUTE_OR_HOME_TOKEN.matcher(command);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.startsWith("~")) {
                return false;
            }
            if (!FilesystemScope.withinRoots(token, workingDir, List.of())) {
                return false;
            }
        }
        return true;
    }
}
