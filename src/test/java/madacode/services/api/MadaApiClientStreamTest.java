package madacode.services.api;

import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MadaApiClientStreamTest {

    @Test
    void thinkingBlockParsedAndDelivered() {
        List<String> sse = List.of(
                sse("message_start", """
                        {"type":"message_start","message":{"model":"test","usage":{"input_tokens":10,"output_tokens":0}}}"""),
                sse("content_block_start", """
                        {"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
                sse("content_block_delta", """
                        {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"let me "}}"""),
                sse("content_block_delta", """
                        {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think..."}}"""),
                sse("content_block_stop", """
                        {"type":"content_block_stop","index":0}"""),
                sse("content_block_start", """
                        {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}"""),
                sse("content_block_delta", """
                        {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"done"}}"""),
                sse("content_block_stop", """
                        {"type":"content_block_stop","index":1}"""),
                sse("message_delta", """
                        {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""),
                sse("message_stop", """
                        {"type":"message_stop"}"""));

        var thinkingBlocks = new ArrayList<ContentBlock.ThinkingBlock>();
        var textChunks = new ArrayList<String>();
        ApiStreamSink sink = new ApiStreamSink() {
            public void onTextDelta(String chunk)                              { textChunks.add(chunk); }
            public void onToolUseBlock(ContentBlock.ToolUseBlock b)            {}
            public void onThinkingBlock(ContentBlock.ThinkingBlock b)          { thinkingBlocks.add(b); }
            public void onMessageStart(String model, TokenUsage u)             {}
            public void onMessageStop(StopReason sr, TokenUsage u, long t, long T) {}
        };

        var client = new MadaApiClient(testRegistry());
        client.parseStreamingResponse(
                sse.stream(), sink, System.nanoTime(), CancellationToken.never());

        assertEquals(1, thinkingBlocks.size());
        assertEquals("let me think...", thinkingBlocks.get(0).thinking());
        assertEquals(List.of("done"), textChunks);
    }

    @Test
    void noThinkingBlockWhenNoneInStream() {
        List<String> sse = List.of(
                sse("message_start", """
                        {"type":"message_start","message":{"model":"test","usage":{"input_tokens":10,"output_tokens":0}}}"""),
                sse("content_block_start", """
                        {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
                sse("content_block_delta", """
                        {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}"""),
                sse("content_block_stop", """
                        {"type":"content_block_stop","index":0}"""),
                sse("message_delta", """
                        {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""),
                sse("message_stop", """
                        {"type":"message_stop"}"""));

        var thinkingBlocks = new ArrayList<ContentBlock.ThinkingBlock>();
        ApiStreamSink sink = new ApiStreamSink() {
            public void onTextDelta(String chunk)                              {}
            public void onToolUseBlock(ContentBlock.ToolUseBlock b)            {}
            public void onThinkingBlock(ContentBlock.ThinkingBlock b)          { thinkingBlocks.add(b); }
            public void onMessageStart(String model, TokenUsage u)             {}
            public void onMessageStop(StopReason sr, TokenUsage u, long t, long T) {}
        };

        var client = new MadaApiClient(testRegistry());
        client.parseStreamingResponse(
                sse.stream(), sink, System.nanoTime(), CancellationToken.never());

        assertTrue(thinkingBlocks.isEmpty());
    }

    private static String sse(String comment, String data) {
        return "data:" + data;
    }

    private static ProviderRegistry testRegistry() {
        return ProviderRegistry.singleProvider(
                new Provider("test", "sk-test", URI.create("http://localhost"), "test",
                        List.of(new Model("test", 200_000))));
    }
}
