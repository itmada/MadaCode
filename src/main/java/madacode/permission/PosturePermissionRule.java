package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.governance.ApprovalPosture;
import madacode.shell.BashCommandModel;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class PosturePermissionRule implements PermissionRule {

    public static final String SOURCE = "posture";

    private static final Set<String> STATE_TOOLS = Set.of(
            ToolNames.ADD_PROVIDER,
            ToolNames.AGENT,
            ToolNames.ASK_USER_QUESTION,
            ToolNames.LIST_MCP_RESOURCES,
            ToolNames.LONGRUN_ENVIRONMENT_READ,
            ToolNames.LONGRUN_ENVIRONMENT_UPDATE,
            ToolNames.LONGRUN_STATE_TRANSITION,
            ToolNames.MEMORY_SAVE,
            ToolNames.READ_MCP_RESOURCE,
            ToolNames.SKILL,
            ToolNames.WORKER_REPORT);

    private static final Set<String> NETWORK_TOOLS = Set.of(
            ToolNames.WEB_FETCH);

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.POSTURE;
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        ApprovalPosture posture = context.session().capabilityProfile().approvalPosture();

        if (ToolNames.BASH.equals(tool.name())) {
            return evaluateBash(input, posture);
        }

        if (tool.isFileEdit()) {
            return evaluateFileEdit(tool, input, context, posture);
        }

        if (NETWORK_TOOLS.contains(tool.name())) {
            return evaluateStance(posture.network());
        }

        if (STATE_TOOLS.contains(tool.name())) {
            return evaluateStance(posture.state());
        }

        return Optional.empty();
    }

    private Optional<PermissionDecision> evaluateBash(ObjectNode input, ApprovalPosture posture) {
        if (BashCommandModel.parse(input.path("command").asText("")).isBasicReadOnly()) {
            return evaluateStance(posture.read());
        }
        return evaluateStance(posture.exec());
    }

    private Optional<PermissionDecision> evaluateFileEdit(
            Tool<?> tool, ObjectNode input, ToolUseContext context, ApprovalPosture posture) {
        List<String> targets = tool.permissionTargets(input);
        if (targets.isEmpty()) {
            return Optional.empty();
        }

        Path workingDir = context.workingDirectory();
        for (String target : targets) {
            if (FilesystemScope.isDangerousEditTarget(target, workingDir)) {
                return Optional.empty();
            }
            if (!FilesystemScope.withinRoots(target, workingDir, List.of())
                    && posture.edit() != ApprovalPosture.Stance.ALLOW_SILENT) {
                return Optional.empty();
            }
        }
        return evaluateStance(posture.edit());
    }

    private Optional<PermissionDecision> evaluateStance(ApprovalPosture.Stance stance) {
        if (stance == null || stance == ApprovalPosture.Stance.PROMPT) {
            return Optional.empty();
        }
        if (stance == ApprovalPosture.Stance.DENY) {
            return Optional.of(PermissionDecision.deny(
                    "Current approval posture denies this tool category.",
                    layer(),
                    SOURCE));
        }
        return Optional.of(PermissionDecision.allow(layer(), SOURCE));
    }
}
