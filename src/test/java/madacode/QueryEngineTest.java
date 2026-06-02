package madacode;

import madacode.core.engine.*;
import madacode.core.model.*;
import madacode.core.session.*;
import madacode.core.turn.*;
import madacode.core.engine.QueryEngine;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionGate;
import madacode.tool.LongRunStageUpdateTool;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class QueryEngineTest {

    private static final class FakeApiClient implements ApiClient {

        private final Queue<ApiResponse> responses = new ArrayDeque<>();

        private final List<List<Message>> calls = new ArrayList<>();
        private final List<Collection<Tool<?>>> toolDeclarations = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();

        void enqueue(ApiResponse response) {
            responses.add(response);
        }

        int callCount() {
            return calls.size();
        }

        @Override
        public ApiResponse send(List<Message> messages, String systemPrompt, Collection<Tool<?>> tools, ApiStreamSink sink, CancellationToken cancellationToken) {
            calls.add(List.copyOf(messages));
            toolDeclarations.add(List.copyOf(tools));
            systemPrompts.add(systemPrompt);
            if(responses.isEmpty()) {
                throw new AssertionError("no fake api responses!");
            }
            ApiResponse resp = responses.remove();
            if (resp.assistantText() != null && !resp.assistantText().isEmpty()) {
                sink.onTextDelta(resp.assistantText());
            }
            for (ToolCall tc : resp.toolCalls()) {
                sink.onToolUseBlock(new ContentBlock.ToolUseBlock(tc.id(), tc.toolName(), tc.input()));
            }
            return resp;
        }

        List<String> lastToolNames() {
            return toolDeclarations.getLast().stream()
                    .map(Tool::name)
                    .toList();
        }

        String lastSystemPrompt() {
            return systemPrompts.getLast();
        }
    }

    @Test
    void completesWhenModelReturnsTextWithoutMethodCall(){
        FakeApiClient fakeApiClient = new FakeApiClient();
        fakeApiClient.enqueue(new ApiClient.ApiResponse("Hello!",List.of()));

        ToolRegistry toolRegistry = new ToolRegistry();

        QueryEngine queryEngine = new QueryEngine(
                fakeApiClient,
                toolRegistry,
                new SystemPromptBuilder(),
                PermissionGate.permissive()
        );

        ConversationSession session = new ConversationSession();
        TurnResult result = queryEngine.runTurn(session, "hi");

        // session.messages().forEach(m -> System.out.println(m.role() + ": " + m.content()));

        assertEquals(FinishReason.COMPLETED,result.finishReason());
        assertEquals("Hello!",result.finalText());
        assertEquals(1,result.iterations());
        assertEquals(3,session.messages().size());

        assertEquals(MessageRole.USER,session.messages().get(1).role());
        assertEquals("hi",session.messages().get(1).content());
        assertEquals(MessageRole.ASSISTANT,session.messages().get(2).role());
        assertEquals("Hello!",session.messages().get(2).content());
        assertEquals(1,fakeApiClient.callCount());

    }

    @Test
    void defaultsToUnlimitedMaxToolCalls() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        fakeApiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));

        QueryEngine engine = new QueryEngine(
                fakeApiClient, new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());

        TurnResult result = engine.runTurn(new ConversationSession(), "hi");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
    }

    @Test
    void exceedsMaxToolCallsReturnsMaxToolCallsFinishReason() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        ObjectNode input = new ObjectMapper().createObjectNode();
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "using tools",
                List.of(
                        new ToolCall("toolu_1", "stub", input),
                        new ToolCall("toolu_2", "stub", input))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("stub"));

        QueryEngine engine = QueryEngine.builder(
                fakeApiClient, registry, new SystemPromptBuilder(),
                PermissionGate.permissive())
                .maxIterations(15)
                .maxToolCalls(1)
                .build();

        ConversationSession session = new ConversationSession();
        TurnResult result = engine.runTurn(session, "hi");

        assertEquals(FinishReason.MAX_TOOL_CALLS, result.finishReason());
        assertTrue(result.finalText().contains("Reached max tool calls: 1"));
        Message trailingUser = session.messages().getLast();
        assertEquals(MessageRole.USER, trailingUser.role());
        assertEquals(2, trailingUser.contentBlocks().size());
        ContentBlock.ToolResultBlock first = (ContentBlock.ToolResultBlock) trailingUser.contentBlocks().get(0);
        assertEquals("toolu_1", first.toolUseId());
        assertFalse(first.success());
    }

    @Test
    void withinMaxToolCallsLimitExecutesNormally() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        ObjectNode input = new ObjectMapper().createObjectNode();
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "using tools",
                List.of(new ToolCall("toolu_1", "stub", input))));
        fakeApiClient.enqueue(new ApiClient.ApiResponse("all done", List.of()));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("stub"));

        QueryEngine engine = QueryEngine.builder(
                fakeApiClient, registry, new SystemPromptBuilder(),
                PermissionGate.permissive())
                .maxIterations(15)
                .maxToolCalls(3)
                .build();

        TurnResult result = engine.runTurn(new ConversationSession(), "hi");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals("all done", result.finalText());
        assertEquals(2, fakeApiClient.callCount());
    }

    @Test
    void maxToolCallsAccumulatesAcrossIterations() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        ObjectNode input = new ObjectMapper().createObjectNode();
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "first batch",
                List.of(new ToolCall("toolu_1", "stub", input))));
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "second batch",
                List.of(new ToolCall("toolu_2", "stub", input))));
        fakeApiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("stub"));

        QueryEngine engine = QueryEngine.builder(
                fakeApiClient, registry, new SystemPromptBuilder(),
                PermissionGate.permissive())
                .maxIterations(15)
                .maxToolCalls(2)
                .build();

        TurnResult result = engine.runTurn(new ConversationSession(), "hi");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals(3, fakeApiClient.callCount());
    }

    @Test
    void maxToolCallsExceededOnSecondIteration() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        ObjectNode input = new ObjectMapper().createObjectNode();
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "first batch",
                List.of(new ToolCall("toolu_1", "stub", input))));
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "second batch would exceed",
                List.of(
                        new ToolCall("toolu_2", "stub", input),
                        new ToolCall("toolu_3", "stub", input))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("stub"));

        QueryEngine engine = QueryEngine.builder(
                fakeApiClient, registry, new SystemPromptBuilder(),
                PermissionGate.permissive())
                .maxIterations(15)
                .maxToolCalls(1)
                .build();

        ConversationSession session = new ConversationSession();
        TurnResult result = engine.runTurn(session, "hi");

        assertEquals(FinishReason.MAX_TOOL_CALLS, result.finishReason());
        assertTrue(result.finalText().contains("Reached max tool calls: 1"));
        assertEquals(2, fakeApiClient.callCount());
        Message trailingUser = session.messages().getLast();
        assertEquals(MessageRole.USER, trailingUser.role());
        assertEquals(2, trailingUser.contentBlocks().size());
        assertEquals("toolu_2",
                ((ContentBlock.ToolResultBlock) trailingUser.contentBlocks().get(0)).toolUseId());
        assertEquals("toolu_3",
                ((ContentBlock.ToolResultBlock) trailingUser.contentBlocks().get(1)).toolUseId());
    }

    @Test
    void maxTokensStopReasonDoesNotReturnCompleted() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "partial answer",
                List.of(),
                StopReason.MAX_TOKENS_REACHED,
                TokenUsage.ZERO));

        QueryEngine engine = new QueryEngine(
                fakeApiClient,
                new ToolRegistry(),
                new SystemPromptBuilder(),
                PermissionGate.permissive());

        TurnResult result = engine.runTurn(new ConversationSession(), "hi");

        assertEquals(FinishReason.MODEL_TRUNCATED, result.finishReason());
        assertFalse(result.completed());
    }

    @Test
    void longRunningToolDeclarationsAreFilteredByStage() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        fakeApiClient.enqueue(new ApiClient.ApiResponse("planning", List.of()));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("file_read"));
        registry.register(new StubTool("plan_get"));
        registry.register(new StubTool("plan_list"));
        registry.register(new StubTool("longrun_stage_update"));
        registry.register(new StubTool("longrun_task_update"));

        QueryEngine engine = new QueryEngine(
                fakeApiClient,
                registry,
                new SystemPromptBuilder(),
                PermissionGate.permissive());
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        engine.runTurn(session, "finish planning");

        assertTrue(fakeApiClient.lastToolNames().contains("longrun_stage_update"));
        assertFalse(fakeApiClient.lastToolNames().contains("longrun_task_update"));
        assertFalse(fakeApiClient.lastToolNames().contains("plan_get"));
        assertFalse(fakeApiClient.lastToolNames().contains("plan_list"));
        assertTrue(fakeApiClient.lastSystemPrompt().contains("Available tools: file_read, longrun_stage_update"));

        FakeApiClient executingClient = new FakeApiClient();
        executingClient.enqueue(new ApiClient.ApiResponse("executing", List.of()));
        QueryEngine executingEngine = new QueryEngine(
                executingClient,
                registry,
                new SystemPromptBuilder(),
                PermissionGate.permissive());
        ConversationSession executing = new ConversationSession();
        executing.setWorkflowMode(SessionMode.LONG_RUNNING);
        executing.setLongRunningStage(LongRunningStage.EXECUTING);

        executingEngine.runTurn(executing, "continue");

        assertFalse(executingClient.lastToolNames().contains("longrun_stage_update"));
        assertTrue(executingClient.lastToolNames().contains("longrun_task_update"));
        assertTrue(executingClient.lastSystemPrompt().contains("Available tools: file_read, longrun_task_update"));
    }

    @Test
    void highConfidenceLongRunningStageUpdateStopsCurrentTurn() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("intent", "FINALIZE_PLAN");
        input.put("confidence", "high");
        input.put("summary", "User confirmed the plan is ready for execution.");
        fakeApiClient.enqueue(new ApiClient.ApiResponse(
                "recording stage update",
                List.of(new ToolCall("toolu_stage", "longrun_stage_update", input))));
        fakeApiClient.enqueue(new ApiClient.ApiResponse("should not be requested", List.of()));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new LongRunStageUpdateTool());

        QueryEngine engine = QueryEngine.builder(
                fakeApiClient,
                registry,
                new SystemPromptBuilder(),
                PermissionGate.permissive())
                .maxIterations(15)
                .build();
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        TurnResult result = engine.runTurn(session, "finalize the plan");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals("Long-running stage transition recorded.", result.finalText());
        assertEquals(1, result.iterations());
        assertEquals(1, fakeApiClient.callCount());
        assertTrue(session.lastLongRunningStageUpdate().isPresent());
        assertEquals(ConversationSession.LongRunningConfidence.HIGH,
                session.lastLongRunningStageUpdate().orElseThrow().confidence());
        assertEquals(MessageRole.ASSISTANT, session.messages().getLast().role());
        assertDoesNotThrow(() -> session.addMessage(Message.user("还要商讨具体细节")));
    }

    @Test
    void apiFailureMidStreamDoesNotProduceConsecutiveAssistant() {
        // Regression for Bug 2: previously the error path called
        // stream.finalizeAndAppend() and then completeWithApiError did another
        // session.addMessage(Message.assistant(...)), producing two consecutive
        // assistant messages — invalid per Anthropic API and breaks resume.
        ApiClient failing = new ApiClient() {
            @Override
            public ApiResponse send(List<Message> messages, String systemPrompt,
                                    Collection<Tool<?>> tools, ApiStreamSink sink,
                                    CancellationToken cancellationToken) {
                sink.onTextDelta("partial thought ");
                throw new madacode.services.api.ApiClientException("boom");
            }
        };

        QueryEngine engine = new QueryEngine(
                failing, new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());

        ConversationSession session = new ConversationSession();
        TurnResult result = engine.runTurn(session, "hi");

        assertEquals(FinishReason.API_ERROR, result.finishReason());

        // No two consecutive assistant messages.
        List<Message> messages = session.messages();
        for (int i = 1; i < messages.size(); i++) {
            MessageRole prev = messages.get(i - 1).role();
            MessageRole curr = messages.get(i).role();
            if (prev == MessageRole.ASSISTANT && curr == MessageRole.ASSISTANT) {
                fail("found consecutive assistant messages at index " + (i - 1) + "," + i
                        + ": " + messages);
            }
        }
        // The partial stream was abandoned; only one assistant (the error message).
        long assistantCount = messages.stream()
                .filter(m -> m.role() == MessageRole.ASSISTANT).count();
        assertEquals(1, assistantCount, "exactly one assistant message expected");
        assertTrue(messages.getLast().content().contains("Model request failed"));
    }

    @Test
    void sessionRejectsAddMessageWithOpenStream() {
        ConversationSession session = new ConversationSession();
        session.beginAssistantStream();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> session.addMessage(Message.user("hi")));
        assertTrue(ex.getMessage().contains("stream is open"));
    }

    @Test
    void sessionRejectsConsecutiveSameRole() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("first"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> session.addMessage(Message.user("second")));
        assertTrue(ex.getMessage().contains("Consecutive"));
    }

    @Test
    void sessionAllowsConsecutiveSystemMessages() {
        // System messages are meta (compact markers, warnings) — multiple in a row are OK.
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.system("first"));
        session.addMessage(Message.system("second"));
        // last index = 2 (init system + two we added)
        assertEquals(MessageRole.SYSTEM, session.messages().getLast().role());
    }

    private static final class StubTool implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }

        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }
}
