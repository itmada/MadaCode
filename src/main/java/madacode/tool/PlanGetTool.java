package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.plan.PlanItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

public class PlanGetTool implements Tool<PlanGetTool.Input> {

    public record Input(String id) {}

    @Override
    public String name() {
        return "plan_get";
    }

    @Override
    public String description() {
        return "Get full details of a single plan item by ID.";
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
        properties.set("id", ToolSchemas.stringProperty(mapper, "Plan item ID"));
        return ToolSchemas.objectSchema(mapper, properties, "id");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        ConversationSession session = context.session();
        String id = input.id() == null ? "" : input.id();
        if (id.isBlank()) {
            return new ToolResult(name(), false, "id is required");
        }

        Optional<PlanItem> found = session.plan().find(id);
        if (found.isEmpty()) {
            return new ToolResult(name(), false, "Plan item not found: " + id);
        }

        return new ToolResult(name(), true, formatPlanItem(found.get(), session));
    }

    static String formatPlanItem(PlanItem item, ConversationSession session) {
        var blockedBy = session.plan().validateCanStart(item);
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(item.id()).append("\n");
        sb.append("title: ").append(item.title()).append("\n");
        sb.append("status: ").append(item.status()).append("\n");
        if (!item.description().isBlank()) {
            sb.append("description: ").append(item.description()).append("\n");
        }
        if (!item.blockedBy().isEmpty()) {
            sb.append("blocked_by: ").append(String.join(", ", item.blockedBy()));
            if (!blockedBy.isEmpty()) {
                sb.append(" (still blocked by: ").append(String.join(", ", blockedBy)).append(")");
            }
            sb.append("\n");
        }
        if (!item.activeForm().isEmpty()) {
            sb.append("active_form: ").append(item.activeForm()).append("\n");
        }
        sb.append("created: ").append(item.createdAt()).append("\n");
        sb.append("updated: ").append(item.updatedAt()).append("\n");
        return sb.toString().stripTrailing();
    }
}
