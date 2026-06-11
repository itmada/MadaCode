package madacode.services.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.core.model.ToolCall;
import madacode.core.turn.CancellationToken;
import madacode.logging.DefaultDiagnosticEvents;
import madacode.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicStreamParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicStreamParser parser = new AnthropicStreamParser(
            mapper,
            new DefaultDiagnosticEvents());

    @Test
    void parsesTextUsageAndStopReason() {
        RecordingSink sink = new RecordingSink();

        ApiClient.ApiResponse response = parser.parse(request(
                Stream.of(
                        data("""
                                {"type":"message_start","message":{"model":"claude-test","usage":{"input_tokens":7}}}
                                """),
                        data("""
                                {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello "}}
                                """),
                        data("""
                                {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
                                """),
                        data("""
                                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9,"cache_creation_input_tokens":2,"cache_read_input_tokens":3}}
                                """),
                        data("""
                                {"type":"message_stop"}
                                """)),
                sink,
                List.of(),
                null));

        assertEquals("Hello world", response.assistantText());
        assertEquals(List.of("Hello ", "world"), sink.textDeltas);
        assertEquals(StopReason.END_TURN, response.stopReason());
        assertEquals(new TokenUsage(7, 9, 2, 3), response.usage());
        assertEquals("claude-test", sink.model);
        assertEquals(response.stopReason(), sink.stopReason);
        assertEquals(response.usage(), sink.finalUsage);
        assertTrue(sink.ttftMs >= 0);
        assertTrue(sink.totalMs >= 0);
    }

    @Test
    void accumulatesToolUseJsonDeltasAndValidatesRequiredFields() {
        RecordingSink sink = new RecordingSink();
        TestTool tool = new TestTool("write_file", List.of("path", "content"));

        ApiClient.ApiResponse response = parser.parse(request(
                Stream.of(
                        data("""
                                {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"write_file"}}
                                """),
                        data("""
                                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.txt\\","}}
                                """),
                        data("""
                                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"content\\":\\"hello\\"}"}}
                                """),
                        data("""
                                {"type":"content_block_stop","index":0}
                                """),
                        data("""
                                {"type":"message_stop"}
                                """)),
                sink,
                List.of(tool),
                null));

        assertEquals(1, response.toolCalls().size());
        ToolCall call = response.toolCalls().getFirst();
        assertEquals("write_file", call.toolName());
        assertEquals("a.txt", call.input().path("path").asText());
        assertEquals("hello", call.input().path("content").asText());
        ContentBlock.ToolUseBlock block = assertInstanceOf(ContentBlock.ToolUseBlock.class, sink.blocks.getFirst());
        assertEquals("write_file", block.name());
    }

    @Test
    void rejectsToolInputMissingRequiredFields() {
        RecordingSink sink = new RecordingSink();
        TestTool tool = new TestTool("write_file", List.of("path", "content"));

        ApiClientException error = assertThrows(ApiClientException.class, () -> parser.parse(request(
                Stream.of(
                        data("""
                                {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"write_file"}}
                                """),
                        data("""
                                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.txt\\"}"}}
                                """),
                        data("""
                                {"type":"content_block_stop","index":0}
                                """)),
                sink,
                List.of(tool),
                null)));

        assertTrue(error.getMessage().contains("missing=[content]"), error.getMessage());
    }

    @Test
    void emitsThinkingBlocks() {
        RecordingSink sink = new RecordingSink();

        parser.parse(request(
                Stream.of(
                        data("""
                                {"type":"content_block_start","index":1,"content_block":{"type":"thinking"}}
                                """),
                        data("""
                                {"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"step 1"}}
                                """),
                        data("""
                                {"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":" + step 2"}}
                                """),
                        data("""
                                {"type":"content_block_stop","index":1}
                                """)),
                sink,
                List.of(),
                null));

        ContentBlock.ThinkingBlock block = assertInstanceOf(ContentBlock.ThinkingBlock.class, sink.blocks.getFirst());
        assertEquals("step 1 + step 2", block.thinking());
    }

    @Test
    void throwsOnErrorEvent() {
        RecordingSink sink = new RecordingSink();

        ApiClientException error = assertThrows(ApiClientException.class, () -> parser.parse(request(
                Stream.of(data("""
                        {"type":"error","error":{"message":"stream exploded"}}
                        """)),
                sink,
                List.of(),
                null)));

        assertEquals("stream exploded", error.getMessage());
    }

    @Test
    void preservesInitialToolInputObjectWhenNoDeltasArrive() {
        RecordingSink sink = new RecordingSink();
        TestTool tool = new TestTool("echo", List.of("value"));

        ApiClient.ApiResponse response = parser.parse(request(
                Stream.of(
                        data("""
                                {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"echo","input":{"value":"hello"}}}
                                """),
                        data("""
                                {"type":"content_block_stop","index":0}
                                """)),
                sink,
                List.of(tool),
                null));

        assertEquals("hello", response.toolCalls().getFirst().input().path("value").asText());
    }

    @Test
    void collectsRawResponseLinesWhenRequested() {
        RecordingSink sink = new RecordingSink();
        List<String> rawLines = new ArrayList<>();

        parser.parse(request(
                Stream.of(
                        "event: message",
                        data("""
                                {"type":"message_stop"}
                                """)),
                sink,
                List.of(),
                rawLines));

        assertEquals(List.of(
                "event: message",
                "data: {\"type\":\"message_stop\"}"), rawLines);
    }

    private AnthropicStreamParser.ParseRequest request(
            Stream<String> lines,
            RecordingSink sink,
            Collection<Tool<?>> tools,
            List<String> rawLines) {
        return new AnthropicStreamParser.ParseRequest(
                lines,
                sink,
                System.nanoTime(),
                CancellationToken.never(),
                tools,
                rawLines);
    }

    private static String data(String json) {
        return "data: " + json.strip();
    }

    private static final class RecordingSink implements ApiStreamSink {
        private final List<String> textDeltas = new ArrayList<>();
        private final List<ContentBlock> blocks = new ArrayList<>();
        private String model;
        private TokenUsage initialUsage;
        private StopReason stopReason;
        private TokenUsage finalUsage;
        private long ttftMs = -1;
        private long totalMs = -1;

        @Override
        public void onTextDelta(String chunk) {
            textDeltas.add(chunk);
        }

        @Override
        public void onToolUseBlock(ContentBlock.ToolUseBlock block) {
            blocks.add(block);
        }

        @Override
        public void onThinkingBlock(ContentBlock.ThinkingBlock block) {
            blocks.add(block);
        }

        @Override
        public void onMessageStart(String model, TokenUsage initialUsage) {
            this.model = model;
            this.initialUsage = initialUsage;
        }

        @Override
        public void onMessageStop(StopReason stopReason, TokenUsage usage, long ttftMs, long totalMs) {
            this.stopReason = stopReason;
            this.finalUsage = usage;
            this.ttftMs = ttftMs;
            this.totalMs = totalMs;
        }
    }

    private final class TestTool implements Tool<ObjectNode> {
        private final String name;
        private final List<String> requiredFields;

        private TestTool(String name, List<String> requiredFields) {
            this.name = name;
            this.requiredFields = requiredFields;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public Class<ObjectNode> inputType() {
            return ObjectNode.class;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            var required = schema.putArray("required");
            requiredFields.forEach(required::add);
            return schema;
        }

        @Override
        public madacode.core.model.ToolResult execute(
                ObjectNode input,
                madacode.core.engine.ToolUseContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
