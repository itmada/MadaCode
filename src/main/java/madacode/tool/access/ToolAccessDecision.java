package madacode.tool.access;

/**
 * Decision produced by the tool access layer for one tool in one runtime
 * context.
 *
 * <p>Visibility answers whether a tool should be declared to the model now.
 * Loadability answers whether {@code tool_search} may load it for a future
 * request. Callability answers whether execution may proceed before
 * input-level permission checks.
 */
public record ToolAccessDecision(
        String toolName,
        boolean visibleNow,
        boolean loadableBySearch,
        boolean callableNow,
        String denialReason) {

    public static ToolAccessDecision allowVisible(String toolName) {
        return new ToolAccessDecision(toolName, true, false, true, null);
    }

    public static ToolAccessDecision allowLoadable(String toolName) {
        return new ToolAccessDecision(toolName, false, true, false, null);
    }

    public static ToolAccessDecision deny(String toolName, String reason) {
        return new ToolAccessDecision(toolName, false, false, false, reason);
    }

    public boolean denied() {
        return denialReason != null;
    }
}
