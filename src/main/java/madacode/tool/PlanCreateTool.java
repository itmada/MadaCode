package madacode.tool;

import madacode.core.ConversationSession;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.plan.PlanItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PlanCreateTool implements Tool<PlanCreateTool.Input> {

    // JSON field name "tasks" kept for backward-compat with any persisted schema snapshots.
    public record Input(List<PlanItemInput> tasks) {}

    public record PlanItemInput(
            String title,
            String description,
            String activeForm,
            List<String> blockedBy) {}

    @Override
    public String name() {
        return "plan_create";
    }

    @Override
    public String description() {
        return "Creates plan items to track progress on complex work. "
                + "Decompose the user's request into executable subtasks. "
                + "Each plan item can declare dependencies via blockedBy (blockedBy item ids). "
                + "Keep exactly one plan item IN_PROGRESS unless parallel work is justified. "
                + "Do not start a plan item until its blockedBy dependencies are COMPLETED. "
                + "Note: this is a planning checklist, not background-running tasks.";
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
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode taskItem = mapper.createObjectNode();
        taskItem.put("type", "object");
        ObjectNode taskProps = mapper.createObjectNode();
        taskProps.set("title", ToolSchemas.stringProperty(mapper, "Short plan item title"));
        taskProps.set("description", ToolSchemas.stringProperty(mapper, "What this plan item involves"));
        taskProps.set("activeForm", ToolSchemas.stringProperty(mapper,
                "Present continuous form shown in spinner when in_progress (e.g., 'Fixing bug')"));
        taskProps.set("blockedBy", ToolSchemas.arrayProperty(mapper,
                "Plan item IDs that must be completed before this one",
                ToolSchemas.stringProperty(mapper, "Plan item ID")));
        taskItem.set("properties", taskProps);
        ArrayNode taskRequired = mapper.createArrayNode();
        taskRequired.add("title");
        taskItem.set("required", taskRequired);

        ObjectNode properties = mapper.createObjectNode();
        properties.set("tasks", ToolSchemas.arrayProperty(mapper,
                "List of plan items to create", taskItem));
        return ToolSchemas.objectSchema(mapper, properties, "tasks");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        ConversationSession session = context.session();
        List<PlanItemInput> tasks = input.tasks();
        if (tasks == null || tasks.isEmpty()) {
            return new ToolResult(name(), false, "tasks array is required and must not be empty");
        }

        List<String> plannedIds = plannedIds(session, tasks.size());
        Set<String> existingIds = session.plan().items().stream()
                .map(PlanItem::id)
                .collect(Collectors.toSet());
        existingIds.addAll(plannedIds);

        Map<String, List<String>> dependencyGraph = new HashMap<>();
        for (PlanItem existing : session.plan().items()) {
            dependencyGraph.put(existing.id(), existing.blockedBy());
        }

        for (int i = 0; i < tasks.size(); i++) {
            PlanItemInput item = tasks.get(i);
            List<String> blockedBy = item.blockedBy() == null ? List.of() : item.blockedBy();
            for (String depId : blockedBy) {
                if (!existingIds.contains(depId)) {
                    return new ToolResult(name(), false,
                            "Unknown dependency: " + depId + " for planned item " + plannedIds.get(i));
                }
            }
            dependencyGraph.put(plannedIds.get(i), blockedBy);
        }

        for (String plannedId : plannedIds) {
            if (hasCycle(plannedId, dependencyGraph, new HashSet<>(), new HashSet<>())) {
                return new ToolResult(name(), false,
                        "Cyclic dependency detected for plan " + plannedId);
            }
        }

        List<PlanItem> created = new ArrayList<>();
        StringBuilder output = new StringBuilder();

        for (int i = 0; i < tasks.size(); i++) {
            PlanItemInput item = tasks.get(i);
            String title = item.title() == null ? "" : item.title();
            if (title.isBlank()) {
                return new ToolResult(name(), false, "Each plan item must have a non-empty title");
            }
            String description = item.description() == null ? "" : item.description();
            String activeForm = item.activeForm() == null ? "" : item.activeForm();
            List<String> blockedBy = item.blockedBy() == null ? List.of() : item.blockedBy();

            String id = plannedIds.get(i);

            PlanItem planItem = PlanItem.create(id, title, description, blockedBy);
            if (!activeForm.isBlank()) {
                planItem = planItem.withActiveForm(activeForm);
            }

            session.plan().add(planItem);
            created.add(planItem);

            output.append("Created plan ")
                    .append(id).append(" [").append(planItem.status()).append("] ")
                    .append(title);
            if (!blockedBy.isEmpty()) {
                output.append(" (blocked by: ").append(String.join(", ", blockedBy)).append(")");
            }
            output.append("\n");
        }

        return new ToolResult(name(), true, output.toString().stripTrailing());
    }

    private List<String> plannedIds(ConversationSession session, int count) {
        int firstId = Integer.parseInt(session.plan().nextId());
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(String.valueOf(firstId + i));
        }
        return ids;
    }

    private boolean hasCycle(
            String id,
            Map<String, List<String>> graph,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        for (String depId : graph.getOrDefault(id, List.of())) {
            if (hasCycle(depId, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }
}
