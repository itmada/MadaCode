package madacode.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Observes a {@link ConversationSession} for rendering.
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>Live assistan streams: callbacks arrive as
 *       textChunk → blockAppended → … → finalized, in order.</li>
 *   <li>Static messages (user inputs, tool results, and all messages
 *       during replay): callbacks arrive via {@link #onMessageAppended}
 *       — one call per message, in session order.</li>
 *   <li>Tool execution events ({@link #onToolExecutionStarted},
 *       {@link #onToolExecutionCompleted}) fire immediately before and
 *       after execution. {@link #onToolResultAvailable} may arrive before the
 *       persisted {@code ToolResultBlock}; renderers should treat it as
 *       transient UI state only.</li>
 * </ul>
 *
 * <h3>Threading contract</h3>
 * Callbacks may be invoked from any thread:
 * <ul>
 *   <li>The QueryEngine main thread (most {@link #onMessageAppended} calls,
 *       turn boundaries).</li>
 *   <li>The streaming I/O thread (text chunks, block-appended,
 *       {@code onMetaEvent(TokenReport)} via {@code onMessageStop}).</li>
 *   <li>Tool worker virtual threads (tool start/completed/progress events
 *       from concurrent segments and from {@code BashTool}'s reader future).</li>
 * </ul>
 *
 * <p>Implementations MUST be thread-safe. State reads on the originating
 * session inside a callback (e.g. {@link ConversationSession#messages()}) are
 * safe and return a stable immutable snapshot — there is no need to
 * defensively copy.
 */
public interface SessionListener {

    /** A complete message was appended to the session (not streaming). */
    default void onMessageAppended(int index, Message message) {}

    /** A chunk of streaming text arrived for the in-flight assistant message. */
    default void onAssistantTextChunk(int index, String chunk) {}

    /** A non-text block (typically {@code ToolUseBlock}) was appended to the streaming assistant message. */
    default void onAssistantBlockAppended(int index, ContentBlock block) {}

    /** The streaming assistant message was finalized and added to the session. */
    default void onAssistantStreamFinalized(int index) {}

    /** Tool execution is about to begin (transient — not persisted). */
    default void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {}

    /** Tool execution just finished (transient). The matching ToolResultBlock follows via onMessageAppended. */
    default void onToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {}

    /** Tool result content is available before the ordered transcript message is appended. */
    default void onToolResultAvailable(String toolUseId, boolean success, String output) {}

    /** Progress output from a running tool (stdout/stderr lines, MCP progress notifications). */
    default void onToolExecutionProgress(String toolUseId, String progressText) {}

    /**
     * Sanitized lifecycle activity intended for display inside an existing tool
     * card. Unlike {@link #onToolExecutionProgress}, this is not raw tool output
     * and is safe to forward across sub-agent boundaries.
     */
    default void onToolExecutionActivity(String toolUseId, String activityText) {}

    /** Structured metric from a running tool (scan counts, byte counts, phase transitions). */
    default void onToolExecutionMetric(String toolUseId, String metricText) {}

    /** Ephemeral meta event (compact, plan toggle, task update, error, token usage). */
    default void onMetaEvent(MetaEvent meta) {}

    /** Called when a turn completes. Listeners should finalize transient state. */
    default void onTurnEnd() {}
}
