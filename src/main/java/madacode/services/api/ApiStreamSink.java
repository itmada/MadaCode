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
}
