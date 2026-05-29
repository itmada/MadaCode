package madacode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.CancellationToken;
import madacode.core.ContentBlock;
import madacode.core.ConversationSession;
import madacode.core.FinishReason;
import madacode.core.Message;
import madacode.core.QueryEngine;
import madacode.core.ToolCall;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.core.TurnResult;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class ToolProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void queryEnginePassesStructuredToolInputToTool() {
        ObjectNode input = mapper.createObjectNode();
        input.put("value", "hello");

        FakeApiClient apiClient = new FakeApiClient();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "I will call a tool.",
                List.of(new ToolCall("toolu_1", "capture", input))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));

        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        QueryEngine queryEngine = new QueryEngine(
                apiClient,
                registry,
                new SystemPromptBuilder(),
                PermissionGate.permissive());
        ConversationSession session = new ConversationSession();

        TurnResult result = queryEngine.runTurn(session, "run capture");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals(2, result.iterations());
        assertEquals("hello", tool.capturedInput.path("value").asText());

        Message assistantToolMessage = session.messages().get(2);
        ContentBlock block = assistantToolMessage.contentBlocks().get(1);
        ContentBlock.ToolUseBlock toolUseBlock = assertInstanceOf(ContentBlock.ToolUseBlock.class, block);
        assertEquals("hello", toolUseBlock.input().path("value").asText());
    }

    private final class CapturingTool implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }

        private ObjectNode capturedInput;

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public String description() {
            return "Captures structured input.";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = mapper.createObjectNode();
            ObjectNode value = mapper.createObjectNode();
            value.put("type", "string");
            properties.set("value", value);
            schema.set("properties", properties);
            schema.putArray("required").add("value");
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            capturedInput = input;
            return new ToolResult(name(), true, input.path("value").asText());
        }
    }

    private static final class FakeApiClient implements ApiClient {

        private final Queue<ApiResponse> responses = new ArrayDeque<>();

        void enqueue(ApiResponse response) {
            responses.add(response);
        }

        @Override
        public ApiResponse send(
                List<Message> messages,
                String systemPrompt,
                Collection<Tool<?>> tools,
                ApiStreamSink sink,
                CancellationToken cancellationToken) {
            ApiResponse resp = responses.remove();
            if (resp.assistantText() != null && !resp.assistantText().isEmpty()) {
                sink.onTextDelta(resp.assistantText());
            }
            for (ToolCall tc : resp.toolCalls()) {
                sink.onToolUseBlock(new ContentBlock.ToolUseBlock(tc.id(), tc.toolName(), tc.input()));
            }
            return resp;
        }
    }
}
