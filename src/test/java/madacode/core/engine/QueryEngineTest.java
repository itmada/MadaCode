package madacode.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.FinishReason;
import madacode.core.model.Message;
import madacode.core.model.MessageKind;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.core.model.ToolCall;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionListener;
import madacode.core.turn.CancellationToken;
import madacode.core.turn.TurnResult;
import madacode.permission.PermissionGate;
import madacode.prompt.SystemPromptBuilder;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiClientException;
import madacode.services.api.ApiStreamSink;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.VisibleTools;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void responseWithoutToolCallsReturnsCompletedOrModelTruncated() {
        ScriptedApiClient completedApi = new ScriptedApiClient()
                .enqueue(response("done", List.of(), StopReason.END_TURN));
        TurnResult completed = engine(completedApi, new ToolRegistry())
                .runTurn(new ConversationSession(), "hello");

        assertEquals(FinishReason.COMPLETED, completed.finishReason());
        assertEquals("done", completed.finalText());
        assertEquals(1, completed.iterations());

        ScriptedApiClient truncatedApi = new ScriptedApiClient()
                .enqueue(response("partial", List.of(), StopReason.MAX_TOKENS_REACHED));
        TurnResult truncated = engine(truncatedApi, new ToolRegistry())
                .runTurn(new ConversationSession(), "hello");

        assertEquals(FinishReason.MODEL_TRUNCATED, truncated.finishReason());
        assertEquals("partial", truncated.finalText());
        assertEquals(1, truncated.iterations());
    }

    @Test
    void toolCallsAppendOrderedToolResultBlocksThenEnterNextModelIteration() {
        EchoTool echoTool = new EchoTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        List<ToolCall> toolCalls = List.of(
                new ToolCall("toolu_first", "echo", input("first")),
                new ToolCall("toolu_second", "echo", input("second")));
        ScriptedApiClient apiClient = new ScriptedApiClient()
                .enqueue(response("using tools", toolCalls, StopReason.TOOL_USE))
                .enqueue(response("final", List.of(), StopReason.END_TURN));
        ConversationSession session = new ConversationSession();
        session.loadDeferredTool(echoTool.name());

        TurnResult result = engine(apiClient, registry).runTurn(session, "run tools");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals(2, result.iterations());
        assertEquals(List.of("first", "second"), echoTool.executedValues);
        assertEquals(2, apiClient.calls.size());

        Message toolResultMessage = session.messages().get(3);
        assertEquals(MessageRole.USER, toolResultMessage.role());
        assertEquals(2, toolResultMessage.contentBlocks().size());

        ContentBlock.ToolResultBlock firstResult =
                assertInstanceOf(ContentBlock.ToolResultBlock.class,
                        toolResultMessage.contentBlocks().get(0));
        assertEquals("toolu_first", firstResult.toolUseId());
        assertEquals("echo:first", firstResult.content());
        assertTrue(firstResult.success());

        ContentBlock.ToolResultBlock secondResult =
                assertInstanceOf(ContentBlock.ToolResultBlock.class,
                        toolResultMessage.contentBlocks().get(1));
        assertEquals("toolu_second", secondResult.toolUseId());
        assertEquals("echo:second", secondResult.content());
        assertTrue(secondResult.success());

        assertEquals(session.messages().subList(1, 4), apiClient.calls.get(1).messages());
        assertEquals(MessageRole.ASSISTANT, session.messages().get(2).role());
        assertEquals("final", session.messages().get(4).content());
    }

    @Test
    void reachingMaxIterationsAppendsSystemWarningAndReturnsMaxIterations() {
        EchoTool echoTool = new EchoTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        ScriptedApiClient apiClient = new ScriptedApiClient()
                .enqueue(response("again", List.of(
                        new ToolCall("toolu_1", "echo", input("first"))), StopReason.TOOL_USE));
        ConversationSession session = new ConversationSession();
        session.loadDeferredTool(echoTool.name());

        TurnResult result = QueryEngine.builder(
                        apiClient,
                        registry,
                        SystemPromptBuilder.builder().build(),
                        PermissionGate.permissive())
                .maxIterations(1)
                .build()
                .runTurn(session, "loop");

        assertEquals(FinishReason.MAX_ITERATIONS, result.finishReason());
        assertEquals("(Reached max iterations: 1)", result.finalText());
        assertEquals(1, result.iterations());
        Message tail = session.messages().getLast();
        assertEquals(MessageRole.SYSTEM, tail.role());
        assertEquals("(Reached max iterations: 1)", tail.content());
    }

    @Test
    void apiClientExceptionAppendsAssistantTerminalMessageAndReturnsApiError() {
        ScriptedApiClient apiClient = new ScriptedApiClient()
                .enqueue(new ApiClientException("boom"));
        ConversationSession session = new ConversationSession();

        TurnResult result = engine(apiClient, new ToolRegistry()).runTurn(session, "hello");

        assertEquals(FinishReason.API_ERROR, result.finishReason());
        assertEquals("Model request failed: boom", result.finalText());
        Message tail = session.messages().getLast();
        assertEquals(MessageRole.ASSISTANT, tail.role());
        ContentBlock.TerminalBlock terminal =
                assertInstanceOf(ContentBlock.TerminalBlock.class, tail.contentBlocks().get(0));
        assertEquals("Model request failed: boom", terminal.message());
        assertEquals(FinishReason.API_ERROR, terminal.reason());
    }

    @Test
    void cancelledTokenReturnsCancelledAndFiresErrorMetaEvent() {
        CancellationToken cancellationToken = CancellationToken.create();
        cancellationToken.cancel("user stopped");
        ConversationSession session = new ConversationSession();
        RecordingListener listener = new RecordingListener();
        session.addListener(listener);

        TurnResult result = engine(new ScriptedApiClient(), new ToolRegistry())
                .runTurn(session, "hello", context(session, cancellationToken));

        assertEquals(FinishReason.CANCELLED, result.finishReason());
        assertEquals("(Cancelled: user stopped)", result.finalText());
        assertEquals(0, result.iterations());
        assertEquals(List.of(FinishReason.CANCELLED), listener.errorReasons());
    }

    @Test
    void permissionDeniedCancellationReturnsPermissionCancelledWithoutErrorMetaEvent() {
        CancellationToken cancellationToken = CancellationToken.create();
        cancellationToken.cancel(CancellationToken.REASON_PERMISSION_DENIED);
        ConversationSession session = new ConversationSession();
        RecordingListener listener = new RecordingListener();
        session.addListener(listener);

        TurnResult result = engine(new ScriptedApiClient(), new ToolRegistry())
                .runTurn(session, "hello", context(session, cancellationToken));

        assertEquals(FinishReason.PERMISSION_CANCELLED, result.finishReason());
        assertEquals("(Cancelled: permission_denied)", result.finalText());
        assertEquals(0, result.iterations());
        assertFalse(listener.hasError());
    }

    @Test
    void apiClientReceivesProjectedMessagesWithoutSystemMarkers() {
        ScriptedApiClient apiClient = new ScriptedApiClient()
                .enqueue(response("done", List.of(), StopReason.END_TURN));
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("previous user"));
        session.addControllerEvent("runtime", java.util.Map.of("status", "ready"));

        engine(apiClient, new ToolRegistry()).runTurn(session, "next prompt");

        List<Message> sent = apiClient.calls.getFirst().messages();
        assertEquals(1, sent.size());
        assertEquals(MessageRole.USER, sent.getFirst().role());
        assertEquals(MessageKind.STANDARD, sent.getFirst().kind());
        assertTrue(sent.getFirst().content().contains("previous user"));
        assertTrue(sent.getFirst().content().contains("[controller-event][runtime]"));
        assertTrue(sent.getFirst().content().contains("next prompt"));
    }

    private QueryEngine engine(ScriptedApiClient apiClient, ToolRegistry registry) {
        return new QueryEngine(
                apiClient,
                registry,
                SystemPromptBuilder.builder().build(),
                PermissionGate.permissive());
    }

    private ToolUseContext context(ConversationSession session, CancellationToken cancellationToken) {
        return new ToolUseContext(
                session.workingDirectory(),
                session,
                0,
                1,
                cancellationToken);
    }

    private ApiClient.ApiResponse response(
            String assistantText,
            List<ToolCall> toolCalls,
            StopReason stopReason) {
        return new ApiClient.ApiResponse(
                assistantText, toolCalls, stopReason, TokenUsage.ZERO);
    }

    private ObjectNode input(String value) {
        ObjectNode input = mapper.createObjectNode();
        input.put("value", value);
        return input;
    }

    private static final class ScriptedApiClient implements ApiClient {
        private final Queue<Object> script = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();

        ScriptedApiClient enqueue(ApiResponse response) {
            script.add(response);
            return this;
        }

        ScriptedApiClient enqueue(ApiClientException exception) {
            script.add(exception);
            return this;
        }

        @Override
        public ApiResponse send(
                List<Message> messages,
                String systemPrompt,
                VisibleTools tools,
                ApiStreamSink sink,
                CancellationToken cancellationToken) {
            calls.add(new Call(List.copyOf(messages), systemPrompt, tools.tools()));
            Object next = script.remove();
            if (next instanceof ApiClientException exception) {
                throw exception;
            }
            ApiResponse response = (ApiResponse) next;
            if (response.assistantText() != null && !response.assistantText().isEmpty()) {
                sink.onTextDelta(response.assistantText());
            }
            for (ToolCall toolCall : response.toolCalls()) {
                sink.onToolUseBlock(new ContentBlock.ToolUseBlock(
                        toolCall.id(), toolCall.toolName(), toolCall.input()));
            }
            return response;
        }

        private record Call(List<Message> messages, String systemPrompt, List<Tool<?>> tools) {
        }
    }

    private static final class EchoTool implements Tool<EchoInput> {
        private final List<String> executedValues = new ArrayList<>();

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes a value.";
        }

        @Override
        public Class<EchoInput> inputType() {
            return EchoInput.class;
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
        public ToolResult execute(EchoInput input, ToolUseContext context) {
            executedValues.add(input.value());
            return new ToolResult(name(), true, "echo:" + input.value());
        }
    }

    private record EchoInput(String value) {
    }

    private static final class RecordingListener implements SessionListener {
        private final List<MetaEvent.Error> errors = new ArrayList<>();

        @Override
        public void onMetaEvent(MetaEvent meta) {
            if (meta instanceof MetaEvent.Error error) {
                errors.add(error);
            }
        }

        boolean hasError() {
            return !errors.isEmpty();
        }

        List<FinishReason> errorReasons() {
            return errors.stream().map(MetaEvent.Error::reason).toList();
        }
    }
}
