package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.Set;

public class PlanUpdateTool implements Tool<PlanUpdateTool.Input> {

    public record Input(String id, String status) {}

    @Override
    public String name() {
        return "plan_update";
    }

    @Override
    public String description() {
        return "Update a plan item's status. "
                + "Use when starting or completing a plan item. "
                + "Mark an item completed only after it is truly done; do not mark completed if tests fail, "
                + "implementation is partial, or a blocker remains. "
                + "Cannot transition a plan item to IN_PROGRESS if it has incomplete blockedBy dependencies. "
                + "This is a planning checklist — status changes here do not trigger background execution.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isPlanModeSafe() {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("id", ToolSchemas.stringProperty(mapper, "Plan item ID"));
        properties.set("status", ToolSchemas.stringEnumProperty(mapper,
                "New plan item status",
                "in_progress", "completed", "pending"));
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

        PlanItem item = found.get();
        String statusStr = input.status() == null ? "" : input.status();

        if (!statusStr.isBlank()) {
            PlanStatus targetStatus;
            try {
                targetStatus = PlanStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return new ToolResult(name(), false,
                        "Invalid status: " + statusStr
                                + ". Must be: pending, in_progress, completed");
            }

            if (targetStatus == PlanStatus.IN_PROGRESS) {
                Set<String> blockers = session.plan().validateCanStart(item);
                if (!blockers.isEmpty()) {
                    return new ToolResult(name(), false,
                            "Cannot start plan " + id
                                    + ": blocked by incomplete dependencies: "
                                    + String.join(", ", blockers));
                }
            }

            try {
                item = item.transitionTo(targetStatus);
            } catch (IllegalArgumentException e) {
                return new ToolResult(name(), false,
                        "Cannot transition " + id + " from " + item.status() + " to "
                                + targetStatus + ": " + e.getMessage());
            }
        }

        session.plan().update(item);

        return new ToolResult(name(), true, PlanGetTool.formatPlanItem(item, session));
    }
}
