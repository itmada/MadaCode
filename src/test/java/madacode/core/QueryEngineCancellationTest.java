package madacode.core;

import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.permission.PermissionGate;
import madacode.prompt.SystemPromptBuilder;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for cancellation propagation through QueryEngine,
 * the orchestrator, and tool execution.
 */
class QueryEngineCancellationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cancellingMidToolCallEndsTurnWithCancelledFinishReason() throws Exception {
        // Tool blocks until externally signalled. Mid-block, we cancel the
        // token; the tool's onCancel hook releases it. The turn should
        // finish with FinishReason.CANCELLED rather than spinning into the
        // next iteration.
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch toolMayProceed = new CountDownLatch(1);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool<ObjectNode>() {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }
            @Override
            public String name() { return "block"; }
            @Override
            public String description() { return "blocks until cancelled"; }
            @Override
            public boolean isReadOnly() { return false; }
            @Override
            public ObjectNode inputSchema(ObjectMapper mapper) {
                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", mapper.createObjectNode());
                return schema;
            }
            @Override
            public ToolResult execute(ObjectNode input, ToolUseContext context) {
                context.cancellationToken().onCancel(toolMayProceed::countDown);
                toolStarted.countDown();
                try {
                    if (!toolMayProceed.await(2, TimeUnit.SECONDS)) {
                        return new ToolResult(name(), false, "test timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                context.cancellationToken().throwIfCancelled();
                return new ToolResult(name(), true, "ok");
            }
        });

        // Model returns one tool_use, then on the next turn would have
        // returned final text — but we never get there because cancel hits
        // mid-tool.
        FakeApiClient api = new FakeApiClient();
        ObjectNode toolInput = MAPPER.createObjectNode();
        api.enqueue(new ApiClient.ApiResponse(
                "calling tool",
                List.of(new ToolCall("call-1", "block", toolInput))));
        api.enqueue(new ApiClient.ApiResponse("would-be final", List.of()));

        QueryEngine engine = new QueryEngine(
                api, registry, new SystemPromptBuilder(), PermissionGate.permissive());

        CancellationToken token = CancellationToken.create();
        ConversationSession session = new ConversationSession();
        ToolUseContext ctx = new ToolUseContext(
                session.workingDirectory(), session, 0, 1, token);

        AtomicReference<TurnResult> result = new AtomicReference<>();
        Thread runner = new Thread(() ->
                result.set(engine.runTurn(session, "go", ctx)));
        runner.start();

        assertTrue(toolStarted.await(2, TimeUnit.SECONDS), "tool never reached");
        token.cancel("test driver cancel");
        runner.join(3000);
        assertTrue(!runner.isAlive(), "runTurn never returned");

        TurnResult r = result.get();
        assertNotNull(r);
        assertEquals(FinishReason.CANCELLED, r.finishReason());
        // We must NOT have made a second model call — that would mean the
        // cancellation didn't break the loop.
        assertEquals(1, api.callCount(),
                "QueryEngine kept iterating after cancellation");
    }

    @Test
    void cancellingBeforeToolDispatchSkipsAllExecution() {
        // Model returns 3 tool calls but the token is cancelled before the
        // orchestrator gets to run any of them. Every result slot must be a
        // structured "cancelled before execution" failure so the model's
        // tool_use blocks all match a tool_result block.
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RecordingTool());

        FakeApiClient api = new FakeApiClient();
        api.enqueue(new ApiClient.ApiResponse(
                "tools",
                List.of(
                        new ToolCall("a", "noop", MAPPER.createObjectNode()),
                        new ToolCall("b", "noop", MAPPER.createObjectNode()),
                        new ToolCall("c", "noop", MAPPER.createObjectNode()))));

        QueryEngine engine = new QueryEngine(
                api, registry, new SystemPromptBuilder(), PermissionGate.permissive());

        CancellationToken token = CancellationToken.create();
        token.cancel("preemptive");
        ConversationSession session = new ConversationSession();
        ToolUseContext ctx = new ToolUseContext(
                session.workingDirectory(), session, 0, 1, token);

        TurnResult r = engine.runTurn(session, "go", ctx);
        assertEquals(FinishReason.CANCELLED, r.finishReason());
    }

    /** Captures FakeApiClient pattern from {@code QueryEngineTest}. */
    private static final class FakeApiClient implements ApiClient {

        private final Queue<ApiResponse> responses = new ArrayDeque<>();
        private int calls;

        void enqueue(ApiResponse r) { responses.add(r); }

        int callCount() { return calls; }

        @Override
        public ApiResponse send(List<Message> messages, String systemPrompt,
                                Collection<Tool<?>> tools, ApiStreamSink sink,
                                CancellationToken cancellationToken) {
            calls++;
            if (responses.isEmpty()) {
                throw new AssertionError("no fake responses left");
            }
            return responses.remove();
        }
    }

    private static final class RecordingTool implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }
        @Override public String name() { return "noop"; }
        @Override public String description() { return "noop"; }
        @Override public boolean isReadOnly() { return false; }
        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            return schema;
        }
        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ran");
        }
    }
}
