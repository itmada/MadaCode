package madacode.tool;

import madacode.cli.UserPromptChannel;
import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.model.MetaEvent;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ExitPlanModeTool implements Tool<ExitPlanModeTool.Input> {

    public record Input(String plan_summary) {}

    @Override
    public String name() {
        return "exit_plan_mode";
    }

    @Override
    public String description() {
        return "Exit plan mode and request user approval for the proposed plan. "
                + "Only call this after you have finished designing the plan.";
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
        ObjectNode properties = mapper.createObjectNode();
        properties.set("plan_summary", ToolSchemas.stringProperty(mapper,
                "Brief summary of the plan for user review"));
        return ToolSchemas.objectSchema(mapper, properties, "plan_summary");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        ConversationSession session = context.session();
        if (!session.isPlanMode()) {
            return new ToolResult(name(), false,
                    "Not in plan mode. Use enter_plan_mode first.");
        }

        String planSummary = input.plan_summary() == null ? "" : input.plan_summary().strip();

        UserPromptChannel channel = context.userPrompts();

        if (!channel.isAvailable()) {
            return new ToolResult(name(), false,
                    "No prompt channel available. Plan mode is still active.");
        }

        String prompt = String.format(
                "\n  ═══════════════════════════════════════\n"
                + "  Plan Mode — Review Proposed Plan\n"
                + "  ═══════════════════════════════════════\n"
                + "%s\n"
                + "\n  Execute this plan? (y/n) ",
                planSummary.isBlank() ? "" : "\n  " + planSummary.replace("\n", "\n  ") + "\n");

        if (channel.confirm(prompt)) {
            session.setPlanMode(false);
            session.fireMetaEvent(new MetaEvent.PlanModeExited());
            session.addMessage(Message.system("[plan mode exited — approved]"));
            return new ToolResult(name(), true,
                    "Plan approved. Plan mode exited. Proceed with implementation.");
        }

        session.fireMetaEvent(new MetaEvent.PlanRejected(planSummary));
        session.addMessage(Message.system("[plan rejected — staying in plan mode]"));
        return new ToolResult(name(), false,
                "Plan rejected by user. Plan mode is still active.");
    }
}
