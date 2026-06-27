package madacode.core.model;

import java.util.Objects;

public final class ToolResult {

    public enum TurnControl {
        CONTINUE,
        YIELD_TO_RUNTIME
    }

    private final String toolName;
    private final boolean success;
    private final String output;
    private final TurnControl turnControl;

    public ToolResult(String toolName, boolean success, String output) {
        this(toolName, success, output, TurnControl.CONTINUE);
    }

    public ToolResult(String toolName, boolean success, String output, TurnControl turnControl) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.success = success;
        this.output = output == null ? "" : output;
        this.turnControl = turnControl == null ? TurnControl.CONTINUE : turnControl;
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

    public TurnControl turnControl() {
        return turnControl;
    }
}
