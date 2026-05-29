package madacode.services.api;

import madacode.core.ContentBlock;
import madacode.core.StopReason;
import madacode.core.TokenUsage;

/**
 * Sink for streaming model responses. Implementations translate SSE events
 * into these calls, which {@link madacode.query.QueryEngine} then routes
 * into a {@link madacode.core.StreamingAssistantHandle}.
 */
public interface ApiStreamSink {
    void onTextDelta(String chunk);
    void onToolUseBlock(ContentBlock.ToolUseBlock block);
    void onThinkingBlock(ContentBlock.ThinkingBlock block);
    void onMessageStart(String model, TokenUsage initialUsage);
    void onMessageStop(StopReason stopReason, TokenUsage usage, long ttftMs, long totalMs);
}
