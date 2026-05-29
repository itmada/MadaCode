package madacode.tool;

import madacode.core.ConversationSession;
import madacode.core.Message;
import madacode.core.MetaEvent;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class EnterPlanModeTool implements Tool<EnterPlanModeTool.Input> {

    public record Input() {}

    private static final String PLAN_MODE_INSTRUCTIONS = """
            Entered plan mode.

            In plan mode, you should:
            1. Thoroughly explore the codebase using read, grep, and glob tools
            2. Understand existing patterns and architecture
            3. Consider multiple approaches and their trade-offs
            4. Use ask_user_question if you need to clarify the approach
            5. Design a concrete implementation strategy
            6. When ready, use exit_plan_mode to present your plan for approval

            Remember: DO NOT write or edit any files yet.
            This is a read-only exploration and planning phase.""";

    @Override
    public String name() {
        return "enter_plan_mode";
    }

    @Override
    public String description() {
        return "Enter plan mode for complex tasks requiring exploration and design before writing code. "
                + "Use when the implementation approach is unclear or there are multiple valid approaches.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        return ToolSchemas.objectSchema(mapper, mapper.createObjectNode());
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        ConversationSession session = context.session();
        session.setPlanMode(true);
        session.fireMetaEvent(new MetaEvent.PlanModeEntered());
        session.addMessage(Message.system("[plan mode entered]"));
        return new ToolResult(name(), true, PLAN_MODE_INSTRUCTIONS);
    }
}
