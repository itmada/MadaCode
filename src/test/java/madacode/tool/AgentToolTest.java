package madacode.tool;

import static org.junit.jupiter.api.Assertions.*;

import madacode.agent.BuiltInAgentLoader;
import madacode.agent.AgentDefinition;
import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.CancellationToken;
import madacode.core.ConversationSession;
import madacode.core.FinishReason;
import madacode.core.QueryEngine;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.core.TurnResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

@DisplayName("AgentTool")
class AgentToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private StubAgentRunner stubRunner;
    private AgentTool agentTool;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        stubRunner = new StubAgentRunner();
        agentTool = new AgentTool(stubRunner,
                AgentRegistry.loaded(new BuiltInAgentLoader()));
        context = new ToolUseContext(Path.of("/tmp/test"), new ConversationSession());
    }

    private static ObjectNode input(String prompt) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("description", "do work");
        node.put("prompt", prompt);
        return node;
    }

    private static ObjectNode input(String prompt, String subagentType) {
        ObjectNode node = input(prompt);
        node.put("subagent_type", subagentType);
        return node;
    }

    @Test
    @DisplayName("missing subagent_type defaults to explorer")
    void missingSubagentTypeDefaultsToExplorer() {
        ToolResult result = ToolTestSupport.invoke(agentTool, input("find config files"), context);

        assertTrue(result.success());
        assertEquals(1, stubRunner.calls.size());
        assertEquals("explorer", stubRunner.calls.get(0).definition().agentType());
    }

    @Test
    @DisplayName("blank subagent_type defaults to explorer")
    void blankSubagentTypeDefaultsToExplorer() {
        ToolResult result = ToolTestSupport.invoke(agentTool, input("find config files", "  "), context);

        assertTrue(result.success());
        assertEquals(1, stubRunner.calls.size());
        assertEquals("explorer", stubRunner.calls.get(0).definition().agentType());
    }

    @Test
    @DisplayName("accepts planner subagent_type")
    void acceptsPlannerSubagentType() {
        ToolResult result = ToolTestSupport.invoke(agentTool, input("plan the refactor", "planner"), context);

        assertTrue(result.success());
        assertEquals(1, stubRunner.calls.size());
        assertEquals("planner", stubRunner.calls.get(0).definition().agentType());
        assertEquals("plan the refactor", stubRunner.calls.get(0).input());
    }

    @Test
    @DisplayName("accepts general subagent_type")
    void acceptsGeneralSubagentType() {
        ToolResult result = ToolTestSupport.invoke(agentTool, input("run the build", "general"), context);

        assertTrue(result.success());
        assertEquals(1, stubRunner.calls.size());
        assertEquals("general", stubRunner.calls.get(0).definition().agentType());
    }

    @Test
    @DisplayName("unknown subagent_type returns failure and does not call runner")
    void unknownSubagentTypeReturnsFailure() {
        ToolResult result = ToolTestSupport.invoke(agentTool, input("do stuff", "nonexistent"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Unknown subagent_type"));
        assertTrue(result.output().contains("nonexistent"));
        assertTrue(stubRunner.calls.isEmpty());
    }

    @Test
    @DisplayName("missing prompt returns failure")
    void missingPromptReturnsFailure() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("description", "x");
        ToolResult result = ToolTestSupport.invoke(agentTool, node, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Missing required field: prompt"));
        assertTrue(stubRunner.calls.isEmpty());
    }

    @Test
    @DisplayName("case-insensitive subagent_type lookup")
    void caseInsensitiveSubagentTypeLookup() {
        ToolResult result = ToolTestSupport.invoke(agentTool, input("search", "PLANNER"), context);

        assertTrue(result.success());
        assertEquals(1, stubRunner.calls.size());
        assertEquals("planner", stubRunner.calls.get(0).definition().agentType());
    }

    @Test
    @DisplayName("depth gate rejects when canSpawnSubAgent returns false")
    void depthGateRejectsWhenCannotSpawnSubAgent() {
        ToolUseContext deepContext = new ToolUseContext(
                Path.of("/tmp/test"), new ConversationSession(), 1, 1);

        ToolResult result = ToolTestSupport.invoke(agentTool, input("do stuff"), deepContext);

        assertFalse(result.success());
        assertTrue(result.output().contains("Maximum agent depth reached"));
        assertTrue(stubRunner.calls.isEmpty());
    }

    @Test
    @DisplayName("depth gate allows when canSpawnSubAgent returns true")
    void depthGateAllowsWhenCanSpawnSubAgent() {
        ToolUseContext shallowContext = new ToolUseContext(
                Path.of("/tmp/test"), new ConversationSession(), 0, 3);

        ToolResult result = ToolTestSupport.invoke(agentTool, input("do stuff"), shallowContext);

        assertTrue(result.success());
        assertEquals(1, stubRunner.calls.size());
    }

    @Test
    @DisplayName("passes context to runner")
    void passesContextToRunner() {
        ToolUseContext customContext = new ToolUseContext(
                Path.of("/custom/dir"), new ConversationSession());

        ToolTestSupport.invoke(agentTool, input("hi"), customContext);

        assertSame(customContext, stubRunner.calls.get(0).context());
    }

    @Test
    @DisplayName("non-COMPLETED finish reason yields failure result")
    void nonCompletedFinishReasonYieldsFailure() {
        stubRunner.nextResult = new TurnResult(
                "(Reached max iterations: 15)", FinishReason.MAX_ITERATIONS, 15);

        ToolResult result = ToolTestSupport.invoke(agentTool, input("do stuff"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Sub-agent did not complete"));
        assertTrue(result.output().contains("MAX_ITERATIONS"));
        assertTrue(result.output().contains("Reached max iterations"));
    }

    @Test
    @DisplayName("explorer README lookup succeeds when child model stops after glob result")
    void explorerReadmeLookupSucceedsWhenChildModelStopsAfterGlobResult() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# test\n");
        ObjectNode globInput = MAPPER.createObjectNode();
        globInput.put("pattern", "README*");

        ScriptedApiClient apiClient = new ScriptedApiClient();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "checking README location",
                List.of(new madacode.core.ToolCall("toolu_glob", "glob", globInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("README is at README.md", List.of()));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new GlobTool());
        AgentTool realAgentTool = new AgentTool(new AgentRunner(registry, apiClient, madacode.permission.PermissionGate.permissive()),
                AgentRegistry.loaded(new BuiltInAgentLoader()));
        ToolUseContext realContext = new ToolUseContext(tempDir, new ConversationSession(tempDir));

        ToolResult result = ToolTestSupport.invoke(realAgentTool,
                input("用 explorer 找一下 README 在哪", "explorer"), realContext);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("README.md"), result.output());
        assertEquals(2, apiClient.calls.size(),
                "child agent should call glob once, then make one final-answer model call");
        assertTrue(apiClient.calls.get(1).messages().stream()
                        .anyMatch(message -> message.content().contains("README.md")),
                "second model call should receive the glob tool_result containing README.md");
    }

    @Test
    @DisplayName("explorer README lookup fails only when child model keeps calling tools")
    void explorerReadmeLookupFailsWhenChildModelKeepsCallingTools() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# test\n");
        ObjectNode globInput = MAPPER.createObjectNode();
        globInput.put("pattern", "README*");

        ScriptedApiClient apiClient = new ScriptedApiClient();
        for (int i = 1; i <= QueryEngine.DEFAULT_MAX_ITERATIONS; i++) {
            apiClient.enqueue(new ApiClient.ApiResponse(
                    "still checking",
                    List.of(new madacode.core.ToolCall("toolu_glob_" + i, "glob", globInput))));
        }

        ToolRegistry registry = new ToolRegistry();
        registry.register(new GlobTool());
        AgentTool realAgentTool = new AgentTool(new AgentRunner(registry, apiClient, madacode.permission.PermissionGate.permissive()),
                AgentRegistry.loaded(new BuiltInAgentLoader()));
        ToolUseContext realContext = new ToolUseContext(tempDir, new ConversationSession(tempDir));

        ToolResult result = ToolTestSupport.invoke(realAgentTool,
                input("用 explorer 找一下 README 在哪", "explorer"), realContext);

        assertFalse(result.success());
        assertTrue(result.output().contains("Sub-agent did not complete"), result.output());
        assertTrue(result.output().contains("MAX_ITERATIONS"), result.output());
        assertTrue(apiClient.calls.stream()
                        .skip(1)
                        .anyMatch(call -> call.messages().stream()
                                .anyMatch(message -> message.content().contains("README.md"))),
                "README.md was available in child tool_result before the iteration ceiling");
        assertEquals(QueryEngine.DEFAULT_MAX_ITERATIONS, apiClient.calls.size(),
                "explorer should match the main agent's default max_iterations");
    }

    @Test
    @DisplayName("cancelled sub-agent yields failure result")
    void cancelledSubAgentYieldsFailure() {
        stubRunner.nextResult = new TurnResult(
                "(Cancelled: user)", FinishReason.CANCELLED, 1);

        ToolResult result = ToolTestSupport.invoke(agentTool, input("do stuff"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("CANCELLED"));
    }

    @Test
    @DisplayName("AgentTool reports itself as non-read-only")
    void agentToolNotReadOnly() {
        assertFalse(agentTool.isReadOnly());
    }

    @Test
    @DisplayName("schema falls back to 'no agents loaded' when registry is empty")
    void schemaShowsNoAgentsLoadedWhenRegistryEmpty() {
        AgentTool empty = new AgentTool(stubRunner, new AgentRegistry());
        ObjectNode schema = empty.inputSchema(MAPPER);

        String hint = schema.path("properties").path("subagent_type")
                .path("description").asText();
        assertTrue(hint.contains("no agents loaded"),
                "schema hint should expose empty-registry fallback, was: " + hint);
        assertTrue(empty.description().contains("no agents loaded"),
                "tool description should expose empty-registry fallback");
    }

    private static final class StubAgentRunner extends AgentRunner {

        final List<RunCall> calls = new ArrayList<>();
        TurnResult nextResult;

        StubAgentRunner() {
            super(new ToolRegistry(), new ApiClient() {
                @Override
                public ApiResponse send(java.util.List<madacode.core.Message> messages,
                                        String systemPrompt,
                                        java.util.Collection<Tool<?>> tools,
                                        ApiStreamSink sink,
                                        CancellationToken cancellationToken) {
                    return new ApiResponse("", List.of());
                }
            }, madacode.permission.PermissionGate.permissive());
        }

        @Override
        public TurnResult run(AgentDefinition definition, String input, ToolUseContext context) {
            calls.add(new RunCall(definition, input, context));
            if (nextResult != null) {
                return nextResult;
            }
            return new TurnResult(input + " done", FinishReason.COMPLETED, 1);
        }

        record RunCall(AgentDefinition definition, String input, ToolUseContext context) {
        }
    }

    private static final class ScriptedApiClient implements ApiClient {
        private final Queue<ApiResponse> responses = new ArrayDeque<>();
        private final List<ApiCall> calls = new ArrayList<>();

        void enqueue(ApiResponse response) {
            responses.add(response);
        }

        @Override
        public ApiResponse send(java.util.List<madacode.core.Message> messages,
                                String systemPrompt,
                                Collection<Tool<?>> tools,
                                ApiStreamSink sink,
                                CancellationToken cancellationToken) {
            calls.add(new ApiCall(List.copyOf(messages), systemPrompt,
                    tools.stream().map(Tool::name).toList()));
            return responses.isEmpty() ? new ApiResponse("done", List.of()) : responses.remove();
        }

        record ApiCall(
                List<madacode.core.Message> messages,
                String systemPrompt,
                List<String> toolNames) {}
    }
}
