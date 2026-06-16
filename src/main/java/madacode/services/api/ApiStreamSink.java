package madacode.services.api;

import madacode.core.model.ContentBlock;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;

/**
 * Sink for streaming model responses. Implementations translate SSE events
 * into these calls, which {@link madacode.query.QueryEngine} then routes
 * into a {@link madacode.core.session.StreamingAssistantHandle}.
 */
public interface ApiStreamSink {
    void onTextDelta(String chunk);
    void onToolUseBlock(ContentBlock.ToolUseBlock block);
    void onThinkingBlock(ContentBlock.ThinkingBlock block);
    void onMessageStart(String model, TokenUsage initialUsage);
    void onMessageStop(StopReason stopReason, TokenUsage usage, long ttftMs, long totalMs);

    /**
     * Signals that the current streaming attempt is being abandoned and a fresh
     * attempt will follow — e.g. {@link RetryingApiClient} retrying after a
     * mid-stream network failure.
     *
     * <p>Implementations backed by an accumulating buffer MUST discard
     * everything emitted so far, both internal state and any draft already
     * shown to the user, so the next attempt streams into a clean slate. The
     * default is a no-op for sinks that do not accumulate (e.g. compaction's
     * throwaway sink).
     */
    default void onStreamReset() {}
}
