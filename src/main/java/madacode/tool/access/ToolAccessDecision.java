package madacode.tool.access;

/**
 * Outcome of resolving one tool in one runtime context. The {@link Status} captures
 * the four mutually exclusive states a tool can be in, so illegal combinations
 * (e.g. "visible and loadable at once") are unrepresentable.
 *
 * <ul>
 *   <li>{@code EXPOSED} — declared to the model now and callable.</li>
 *   <li>{@code LOADABLE} — not declared, but {@code tool_search} may load it for a
 *       future request.</li>
 *   <li>{@code HIDDEN} — not declared and not loadable in this context, yet not a
 *       policy violation (e.g. already available, so a search is a no-op).</li>
 *   <li>{@code DENIED} — the capability/workflow policy forbids it; {@link #reason()}
 *       explains why.</li>
 * </ul>
 */
public record ToolAccessDecision(Status status, String toolName, String reason) {

    public enum Status { EXPOSED, LOADABLE, HIDDEN, DENIED }

    public static ToolAccessDecision exposed(String toolName) {
        return new ToolAccessDecision(Status.EXPOSED, toolName, null);
    }

    public static ToolAccessDecision loadable(String toolName) {
        return new ToolAccessDecision(Status.LOADABLE, toolName, null);
    }

    public static ToolAccessDecision hidden(String toolName, String reason) {
        return new ToolAccessDecision(Status.HIDDEN, toolName, reason);
    }

    public static ToolAccessDecision denied(String toolName, String reason) {
        return new ToolAccessDecision(Status.DENIED, toolName, reason);
    }

    /** Declared to the model now, and callable. */
    public boolean exposed() {
        return status == Status.EXPOSED;
    }

    /** {@code tool_search} may load it for a future request. */
    public boolean loadable() {
        return status == Status.LOADABLE;
    }

    /** Forbidden by capability or workflow policy. */
    public boolean denied() {
        return status == Status.DENIED;
    }
}
