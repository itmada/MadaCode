package madacode.agent;

import madacode.core.session.ConversationSession;
import madacode.core.model.MetaEvent;
import madacode.core.session.SessionListener;
import madacode.tool.ToolActivitySummary;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * Bridges a sub-agent {@link ConversationSession} to its parent by forwarding
 * only those events whose semantics are safe to propagate across the
 * agent boundary.
 *
 * <p>Forwarded:
 * <ul>
 *   <li>{@link MetaEvent.TokenReport}, so the parent's token total reflects
 *       sub-agent consumption (billing correctness).</li>
 *   <li>Child {@code onToolExecutionStarted} events, projected into the
 *       parent agent card as a single sanitized activity line. This gives the
 *       parent turn lightweight "child is reading/searching/running" activity
 *       without leaking child transcript or tool output.</li>
 *   <li>Child {@code onToolExecutionActivity} events, which are already
 *       sanitized lifecycle summaries from a deeper descendant. These bubble up
 *       one level at a time so grandchild activity can reach the root agent
 *       card without forwarding raw progress.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> forwarded:
 * <ul>
 *   <li>{@link MetaEvent.Error} — {@code TurnRenderer} reacts to this by
 *       aborting the current turn. Forwarding a sub-agent's terminal Error
     *       (MAX_ITERATIONS, API_ERROR, CANCELLED) would abort
 *       the parent turn mid-flight, bypassing the parent LLM's chance to
 *       react to the failure. Sub-agent failures surface to the parent
 *       through the failing {@code ToolResult} produced by
 *       {@code AgentResults.toToolResult}, which is the correct channel.</li>
 *   <li>Message append / streaming events — sub-agent dialogue is private;
 *       leaking it would pollute the parent message stream and confuse
 *       {@code TurnRenderer} (which assumes one active stream).</li>
 *   <li>Tool raw progress / completion events — raw child progress shares the
 *       same text channel as bash stdout/stderr, so forwarding it would allow
 *       child tool output to leak into the parent tool card. We only forward
 *       typed lifecycle activity, never child stdout/stderr or tool_result
 *       content.</li>
 *   <li>Tool metric events — {@code METRIC} progress lines are internal
 *       measurements (scan counts, byte counts) specific to a tool's execution.
 *       These are not lifecycle projections and should not bubble up to parent
 *       agent cards.</li>
 *   <li>Plan/Compact events — these mutate session-scoped state; a
 *       sub-agent's plan is its own, not the parent's.</li>
 * </ul>
 *
 * <p>For deeper nesting (grandchild → child → parent), each layer's forwarder
 * naturally bubbles forwarded events up one level at a time.
 */
final class ParentEventForwarder implements SessionListener {

    private final ConversationSession parent;
    private final String parentToolUseId;

    ParentEventForwarder(ConversationSession parent, String parentToolUseId) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.parentToolUseId = parentToolUseId;
    }

    @Override
    public void onMetaEvent(MetaEvent meta) {
        if (meta instanceof MetaEvent.TokenReport) {
            parent.fireMetaEvent(meta);
        }
    }

    @Override
    public void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        if (parentToolUseId == null) {
            return;
        }
        parent.fireToolExecutionActivity(parentToolUseId, ToolActivitySummary.asProjectionLine(toolName, input));
    }

    @Override
    public void onToolExecutionActivity(String toolUseId, String activityText) {
        if (parentToolUseId == null) {
            return;
        }
        parent.fireToolExecutionActivity(parentToolUseId, activityText);
    }
}
