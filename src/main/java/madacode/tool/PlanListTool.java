package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.plan.PlanStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PlanListTool implements Tool<PlanListTool.Input> {

    public record Input(String status, Boolean include_completed) {}

    @Override
    public String name() {
        return "plan_list";
    }

    @Override
    public String description() {
        return "List all plan items, optionally filtered by status.";
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
    public boolean isPlanModeSafe() {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("status", ToolSchemas.stringEnumProperty(mapper,
                "Filter by status (omit for all)",
                "pending", "in_progress", "completed"));
        properties.set("include_completed", ToolSchemas.booleanProperty(mapper,
                "Include completed plan items (default true)"));
        return ToolSchemas.objectSchema(mapper, properties);
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        ConversationSession session = context.session();
        String statusFilter = input.status() == null ? "" : input.status();
        boolean includeCompleted = input.include_completed() == null
                ? true
                : input.include_completed();

        var filtered = session.plan().items().stream()
                .filter(t -> {
                    if (!statusFilter.isBlank()) {
                        return t.status().name().equalsIgnoreCase(statusFilter);
                    }
                    if (!includeCompleted) {
                        return t.status() != PlanStatus.COMPLETED;
                    }
                    return true;
                })
                .toList();

        return new ToolResult(name(), true, PlanListFormatter.format(filtered, session));
    }
}
