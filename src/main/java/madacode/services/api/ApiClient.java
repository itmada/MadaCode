package madacode.services.api;

import madacode.core.CancellationToken;
import madacode.core.Message;
import madacode.core.StopReason;
import madacode.core.TokenUsage;
import madacode.core.ToolCall;
import madacode.tool.Tool;

import java.util.Collection;
import java.util.List;

public interface ApiClient {

    ApiResponse send(
            List<Message> messages,
            String systemPrompt,
            Collection<Tool<?>> tools,
            ApiStreamSink sink,
            CancellationToken cancellationToken);

    /** Convenience overload: never-cancelled. */
    default ApiResponse send(
            List<Message> messages,
            String systemPrompt,
            Collection<Tool<?>> tools,
            ApiStreamSink sink) {
        return send(messages, systemPrompt, tools, sink, CancellationToken.never());
    }

    record ApiResponse(
            String assistantText,
            List<ToolCall> toolCalls,
            StopReason stopReason,
            TokenUsage usage) {

        public ApiResponse(String assistantText, List<ToolCall> toolCalls) {
            this(assistantText, toolCalls, StopReason.UNKNOWN, TokenUsage.ZERO);
        }
    }
}
