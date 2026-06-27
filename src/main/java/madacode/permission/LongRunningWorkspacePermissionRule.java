package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.governance.IsolationProfile;
import madacode.shell.BashCommandModel;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class LongRunningWorkspacePermissionRule implements PermissionRule {

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.SCOPE;
    }

    public static final String SOURCE = "long_running_workspace";
    public static final String SOURCE_UNRESOLVED_EXPANSION = "long_running_workspace_unresolved_expansion";
    private static final Set<String> WORKSPACE_READ_TOOLS = Set.of(
            ToolNames.FILE_READ,
            ToolNames.GLOB,
            ToolNames.GREP,
            ToolNames.LONGRUN_ENVIRONMENT_READ);

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!applies(context)) {
            return Optional.empty();
        }

        if (tool.isReadOnly()) {
            if (!WORKSPACE_READ_TOOLS.contains(tool.name())) {
                return Optional.of(PermissionDecision.deny(
                        "Long-running workers may only use workspace-scoped read tools.",
                        layer(),
                        SOURCE));
            }
            Path workingDir = context.workingDirectory();
            for (String target : tool.permissionTargets(input)) {
                if (!FilesystemScope.withinRoots(target, workingDir, List.of())) {
                    return Optional.of(PermissionDecision.deny(
                            "Long-running worker reads must stay inside the workspace.",
                            layer(),
                            SOURCE));
                }
            }
            return Optional.of(PermissionDecision.allow(layer(), SOURCE));
        }

        if ("bash".equals(tool.name())) {
            return Optional.of(evaluateBash(input.path("command").asText(""), context.workingDirectory()));
        }

        if (isWorkerTaskStoreTool(tool)) {
            return Optional.of(PermissionDecision.allow(layer(), SOURCE));
        }

        if (tool.isFileEdit()) {
            List<String> targets = tool.permissionTargets(input);
            if (targets.isEmpty()) {
                return Optional.of(PermissionDecision.deny(
                        "Long-running worker file edits must declare a workspace target.",
                        layer(),
                        SOURCE));
            }

            Path workingDir = context.workingDirectory();
            for (String target : targets) {
                if (!FilesystemScope.withinRoots(target, workingDir, List.of())) {
                    return Optional.of(PermissionDecision.deny(
                            "Long-running worker file edits must stay inside the workspace.",
                            layer(),
                            SOURCE));
                }
                if (FilesystemScope.isDangerousEditTarget(target, workingDir)) {
                    return Optional.of(PermissionDecision.deny(
                            "Long-running workers cannot modify sensitive workspace metadata.",
                            layer(),
                            SOURCE));
                }
            }

            return Optional.of(PermissionDecision.allow(layer(), SOURCE));
        }

        return Optional.of(PermissionDecision.deny(
                "Long-running worker cannot request interactive approval for tool: " + tool.name(),
                layer(),
                SOURCE));
    }

    private static boolean applies(ToolUseContext context) {
        return context.session().isLongRunningWorkerSession()
                && context.session().capabilityProfile().isolationProfile().level()
                        == IsolationProfile.IsolationLevel.LOCAL_UNSAFE;
    }

    private static boolean isWorkerTaskStoreTool(Tool<?> tool) {
        return ToolNames.LONGRUN_ENVIRONMENT_UPDATE.equals(tool.name())
                || ToolNames.WORKER_REPORT.equals(tool.name());
    }

    private static PermissionDecision evaluateBash(String command, Path workingDir) {
        BashScopeDecision decision = BashScopeDecision.evaluate(command, workingDir);
        if (decision.allowed()) {
            return PermissionDecision.allow(
                    PermissionLayer.SCOPE,
                    decision.unresolvedMutation() ? SOURCE_UNRESOLVED_EXPANSION : SOURCE);
        }
        return PermissionDecision.deny(decision.reason(), PermissionLayer.SCOPE, SOURCE);
    }

    private record BashScopeDecision(boolean allowed, String reason, boolean unresolvedMutation) {
        private static final Set<String> ALLOWED_EXTERNAL_READ_COMMANDS = Set.of(
                "ls", "cat", "head", "tail", "less", "more",
                "grep", "rg", "find", "pwd", "wc", "stat", "file",
                "du", "df", "sort", "uniq", "cut", "awk", "sed",
                "git");

        static BashScopeDecision allow() {
            return allow(false);
        }

        static BashScopeDecision allow(boolean unresolvedMutation) {
            return new BashScopeDecision(true, "", unresolvedMutation);
        }

        static BashScopeDecision deny(String reason) {
            return new BashScopeDecision(false, reason, false);
        }

        static BashScopeDecision evaluate(String command, Path workingDir) {
            BashCommandModel model = BashCommandModel.parse(command);
            if (model.isBlank()) {
                return allow();
            }

            Path currentDir = workingDir.toAbsolutePath().normalize();
            boolean unresolvedMutation = false;
            for (BashCommandModel.Segment segment : model.segments()) {
                if (segment.commandName() == null) {
                    continue;
                }
                if ("cd".equals(segment.commandName())) {
                    String target = segment.cdTarget();
                    if (target == null) {
                        currentDir = workingDir.toAbsolutePath().normalize();
                        continue;
                    }
                    if (BashCommandModel.containsExpansion(target)) {
                        return deny("Long-running worker bash cannot use unresolved shell expansion to change directories.");
                    }
                    currentDir = BashCommandModel.resolvePath(target, currentDir);
                    continue;
                }

                boolean currentOutsideWorkspace = !insideWorkspace(currentDir.toString(), workingDir);
                Path effectiveDir = currentDir;
                Path gitWorkTree = segment.gitWorkTree(currentDir);
                if (gitWorkTree != null) {
                    effectiveDir = gitWorkTree;
                }

                ExternalUse externalUse = externalUse(segment, effectiveDir, workingDir);
                if (externalUse.externalWrite()) {
                    return deny("Long-running worker bash cannot modify files outside the workspace.");
                }
                if ((currentOutsideWorkspace || externalUse.externalRead())
                        && !isAllowedExternalReadCommand(segment)) {
                    return deny("Long-running worker bash outside the workspace is limited to read-only inspection commands.");
                }
                if (currentOutsideWorkspace && segment.writesRealFile()) {
                    return deny("Long-running worker bash cannot redirect output while running outside the workspace.");
                }
                if (segment.isMutatingCommand() && segment.hasUnresolvedExpansion()) {
                    unresolvedMutation = true;
                }
            }
            return allow(unresolvedMutation);
        }

        private static ExternalUse externalUse(BashCommandModel.Segment segment, Path currentDir, Path workingDir) {
            boolean externalRead = false;
            boolean externalWrite = false;
            for (String token : segment.pathLikeArgs()) {
                Path path = BashCommandModel.resolvePath(token, currentDir);
                if (!insideWorkspace(path.toString(), workingDir)) {
                    if (segment.isMutatingCommand()) {
                        externalWrite = true;
                    } else {
                        externalRead = true;
                    }
                }
            }
            for (BashCommandModel.Redirection redirection : segment.redirections()) {
                if (!redirection.writesRealFile()) {
                    continue;
                }
                if (!BashCommandModel.isPathLike(redirection.target())) {
                    continue;
                }
                Path path = BashCommandModel.resolvePath(redirection.target(), currentDir);
                if (!insideWorkspace(path.toString(), workingDir)) {
                    externalWrite = true;
                }
            }
            if (segment.findMutation() || segment.inPlaceMutation()) {
                externalWrite = externalRead || externalWrite;
            }
            return new ExternalUse(externalRead, externalWrite);
        }

        private record ExternalUse(boolean externalRead, boolean externalWrite) {}

        private static boolean isAllowedExternalReadCommand(BashCommandModel.Segment segment) {
            if ("git".equals(segment.commandName())) {
                return segment.isReadOnlyGit();
            }
            if ("find".equals(segment.commandName()) && segment.findMutation()) {
                return false;
            }
            if (segment.inPlaceMutation()) {
                return false;
            }
            return ALLOWED_EXTERNAL_READ_COMMANDS.contains(segment.commandName());
        }

        private static boolean insideWorkspace(String rawPath, Path workingDir) {
            return FilesystemScope.withinRoots(rawPath, workingDir, List.of());
        }
    }
}
