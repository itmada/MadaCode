package madacode.tool;

import madacode.core.ConversationSession;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.plan.PlanItem;
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

        if (filtered.isEmpty()) {
            return new ToolResult(name(), true, "(no plan items)");
        }

        StringBuilder sb = new StringBuilder();
        for (PlanItem t : filtered) {
            var stillBlocked = session.plan().validateCanStart(t);
            sb.append("[").append(t.status()).append("] ");
            sb.append(t.id()).append("  ").append(t.title());
            if (!t.blockedBy().isEmpty()) {
                sb.append(" (blocked by: ").append(String.join(", ", t.blockedBy()));
                if (!stillBlocked.isEmpty()) {
                    sb.append(", still blocked by: ").append(String.join(", ", stillBlocked));
                }
                sb.append(")");
            }
            sb.append("\n");
        }
        return new ToolResult(name(), true, sb.toString().stripTrailing());
    }
}
