package madacode.tool;

import madacode.core.ConversationSession;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.plan.TodoItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public class TodoWriteTool implements Tool<TodoWriteTool.Input> {

    public record Input(List<TodoEntry> todos) {}

    public record TodoEntry(String content, String status) {}

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Write a short-lived checklist for the current turn. "
                + "Use for small steps within a task, not for multi-step project planning. "
                + "The list is replaced entirely on each call. "
                + "It resets between turns — rewrite each turn as needed.";
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
        ObjectNode todoItem = mapper.createObjectNode();
        todoItem.put("type", "object");
        ObjectNode todoProps = mapper.createObjectNode();
        todoProps.set("content", ToolSchemas.stringProperty(mapper, "Todo item description"));
        todoProps.set("status", ToolSchemas.stringEnumProperty(mapper,
                "Current status", "pending", "in_progress", "completed"));
        todoItem.set("properties", todoProps);
        ArrayNode todoRequired = mapper.createArrayNode();
        todoRequired.add("content");
        todoRequired.add("status");
        todoItem.set("required", todoRequired);

        ObjectNode properties = mapper.createObjectNode();
        properties.set("todos", ToolSchemas.arrayProperty(mapper,
                "Complete list of todo items (replaces all existing todos)", todoItem));
        return ToolSchemas.objectSchema(mapper, properties, "todos");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        ConversationSession session = context.session();
        if (input.todos() == null) {
            return new ToolResult(name(), false, "todos array is required");
        }

        List<TodoItem> items = new ArrayList<>();
        for (TodoEntry entry : input.todos()) {
            String content = entry.content() == null ? "" : entry.content();
            String status = entry.status() == null || entry.status().isBlank()
                    ? "pending"
                    : entry.status();
            if (content.isBlank()) {
                return new ToolResult(name(), false, "Each todo must have non-empty content");
            }
            try {
                items.add(new TodoItem(content, status));
            } catch (IllegalArgumentException e) {
                return new ToolResult(name(), false, e.getMessage());
            }
        }

        session.plan().replaceTodos(items);

        long pending = items.stream().filter(t -> t.status().equals("pending")).count();
        long inProgress = items.stream().filter(t -> t.status().equals("in_progress")).count();
        long completed = items.stream().filter(t -> t.status().equals("completed")).count();

        return new ToolResult(name(), true,
                String.format("%d pending, %d in_progress, %d completed",
                        pending, inProgress, completed));
    }
}
