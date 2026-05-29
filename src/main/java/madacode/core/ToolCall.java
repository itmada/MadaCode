package madacode.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public final class ToolCall {

    private final String id;
    private final String toolName;
    private final ObjectNode input;

    public ToolCall(String id, String toolName, ObjectNode input) {
        this.id = Objects.requireNonNull(id, "id");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.input = Objects.requireNonNull(input, "input").deepCopy();
    }

    public String id() {
        return id;
    }

    public String toolName() {
        return toolName;
    }

    public ObjectNode input() {
        return input.deepCopy();
    }
}
