package madacode.core;

import java.util.Objects;

public final class ToolResult {

    private final String toolName;
    private final boolean success;
    private final String output;

    public ToolResult(String toolName, boolean success, String output) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.success = success;
        this.output = output == null ? "" : output;
    }

    public String toolName() {
        return toolName;
    }

    public boolean success() {
        return success;
    }

    public String output() {
        return output;
    }
}
