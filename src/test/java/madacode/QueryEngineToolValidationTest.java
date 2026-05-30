package madacode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.session.ConversationSession;
import madacode.core.model.FinishReason;
import madacode.core.model.Message;
import madacode.core.engine.QueryEngine;
import madacode.core.model.ToolCall;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.core.turn.TurnResult;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionDecision;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryEngineToolValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void invalidToolInputReturnsToolResultWithoutPermissionOrExecution() {
        ObjectNode invalidInput = mapper.createObjectNode();
        invalidInput.put("extra", "ignored");

        FakeApiClient apiClient = new FakeApiClient();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "I will call a tool.",
                List.of(new ToolCall("toolu_1", "capture", invalidInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("fixed", List.of()));

        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        AtomicInteger permissionChecks = new AtomicInteger();
        QueryEngine queryEngine = new QueryEngine(
                apiClient,
                registry,
                new SystemPromptBuilder(),
                (requestedTool, input, context) -> {
                    permissionChecks.incrementAndGet();
                    return PermissionDecision.allow();
                });
        ConversationSession session = new ConversationSession();

        TurnResult result = queryEngine.runTurn(session, "run capture");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals("fixed", result.finalText());
        assertEquals(0, permissionChecks.get());
        assertEquals(0, tool.executions());

        Message toolResultMessage = session.messages().get(3);
        ContentBlock.ToolResultBlock toolResult = (ContentBlock.ToolResultBlock)
                toolResultMessage.contentBlocks().getFirst();
        assertEquals("toolu_1", toolResult.toolUseId());
        assertTrue(toolResult.content().contains("Invalid tool input for capture"));
        assertTrue(toolResult.content().contains("missing required field 'value'"));
        assertTrue(toolResult.content().contains("unknown field 'extra'"));
    }

    private final class CapturingTool implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }

        private int executions;

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
            executions++;
            return new ToolResult(name(), true, input.path("value").asText());
        }

        private int executions() {
            return executions;
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
            return responses.remove();
        }
    }
}
