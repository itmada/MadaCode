package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.util.Optional;
import java.util.Set;

public final class DefaultModePermissionRule implements PermissionRule {

    public static final String SOURCE = "default_mode";

    private static final Set<String> BUILT_IN_TOOLS = Set.of(
            ToolNames.ADD_PROVIDER,
            ToolNames.AGENT,
            ToolNames.ASK_USER_QUESTION,
            ToolNames.FILE_READ,
            ToolNames.GLOB,
            ToolNames.GREP,
            ToolNames.LIST_MCP_RESOURCES,
            ToolNames.LONGRUN_ENVIRONMENT_READ,
            ToolNames.LONGRUN_ENVIRONMENT_UPDATE,
            ToolNames.LONGRUN_STATE_TRANSITION,
            ToolNames.MEMORY_SAVE,
            ToolNames.READ_MCP_RESOURCE,
            ToolNames.SKILL,
            ToolNames.TOOL_SEARCH,
            ToolNames.UPDATE_PLAN,
            ToolNames.WEB_FETCH,
            ToolNames.WORKER_REPORT);

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        PermissionMode mode = context.session().permissionMode();
        if (mode != PermissionMode.DEFAULT && mode != PermissionMode.EDIT) {
            return Optional.empty();
        }

        if (ToolNames.BASH.equals(tool.name())) {
            if (BashCommandClassifier.isBasicReadOnly(input.path("command").asText(""))) {
                return Optional.of(PermissionDecision.allow(SOURCE));
            }
            return Optional.empty();
        }

        if (tool.isFileEdit()) {
            return Optional.empty();
        }

        if (BUILT_IN_TOOLS.contains(tool.name())) {
            return Optional.of(PermissionDecision.allow(SOURCE));
        }

        return Optional.empty();
    }
}
