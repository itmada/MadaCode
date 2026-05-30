package madacode.eval;

import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.core.model.ToolCall;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.tool.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MockApiClient implements ApiClient {

    private final List<MockApiResponse> cannedResponses;
    private int callIndex;

    public MockApiClient(List<MockApiResponse> responses) {
        this.cannedResponses = List.copyOf(responses);
        this.callIndex = 0;
    }

    @Override
    public ApiResponse send(
            List<Message> messages,
            String systemPrompt,
            Collection<Tool<?>> tools,
            ApiStreamSink sink,
            CancellationToken cancellationToken) {
        if (callIndex >= cannedResponses.size()) {
            throw new IllegalStateException(
                    "No more canned responses (requested #" + (callIndex + 1)
                            + ", have " + cannedResponses.size() + ")");
        }
        MockApiResponse canned = cannedResponses.get(callIndex++);

        // Simulate streaming: emit text delta
        if (canned.assistantText() != null && !canned.assistantText().isBlank()) {
            sink.onTextDelta(canned.assistantText());
        }
        // Emit tool use blocks if present
        if (canned.toolCalls() != null) {
            for (var stub : canned.toolCalls()) {
                sink.onToolUseBlock(new ContentBlock.ToolUseBlock(
                        stub.id(), stub.name(), stub.input()));
            }
        }
        sink.onMessageStart("mock-model", TokenUsage.ZERO);
        sink.onMessageStop(StopReason.END_TURN, TokenUsage.ZERO, 0, 0);

        List<ToolCall> toolCalls = new ArrayList<>();
        if (canned.toolCalls() != null) {
            for (var stub : canned.toolCalls()) {
                toolCalls.add(stub.toToolCall());
            }
        }

        return new ApiResponse(
                canned.assistantText(),
                toolCalls.isEmpty() ? null : toolCalls,
                StopReason.END_TURN,
                TokenUsage.ZERO);
    }
}
