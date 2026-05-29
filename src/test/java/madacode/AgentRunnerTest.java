package madacode;

import madacode.agent.AgentDefinition;
import madacode.agent.AgentRunner;
import madacode.permission.PermissionGate;
import madacode.permission.PermissionMode;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.CancellationToken;
import madacode.core.ConversationSession;
import madacode.core.Message;
import madacode.core.FinishReason;
import madacode.core.MetaEvent;
import madacode.core.SessionListener;
import madacode.core.TokenUsage;
import madacode.core.ToolCall;
import madacode.core.ToolExecutor;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.core.TurnResult;
import madacode.tool.Tool;
import madacode.tool.ToolActivitySummary;
import madacode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgentRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void filtersToolsByAllowedTools() {
        CapturingApiClient apiClient = completedApiClient();
        AgentRunner runner = new AgentRunner(registry("read", "grep", "bash", "agent", "extra"), apiClient, PermissionGate.permissive());
        AgentDefinition definition = definition(
                Set.of("read", "grep"),
                Set.of(),
                5);

        runner.run(definition, "inspect", context());

        assertEquals(List.of("read", "grep"), apiClient.calls.getFirst().toolNames());
    }

    @Test
    void excludesDisallowedToolsWhenAllowedToolsIsEmpty() {
        CapturingApiClient apiClient = completedApiClient();
        AgentRunner runner = new AgentRunner(registry("read", "grep", "bash", "agent"), apiClient, PermissionGate.permissive());
        AgentDefinition definition = definition(
                Set.of(),
                Set.of("bash"),
                5);

        runner.run(definition, "inspect", context());

        assertEquals(List.of("read", "grep"), apiClient.calls.getFirst().toolNames());
    }

    @Test
    void alwaysExcludesAgentToolEvenWhenAllowed() {
        CapturingApiClient apiClient = completedApiClient();
        AgentRunner runner = new AgentRunner(registry("read", "agent"), apiClient, PermissionGate.permissive());
        AgentDefinition definition = definition(
                Set.of("read", "agent"),
                Set.of(),
                5);

        runner.run(definition, "inspect", context());

        assertEquals(List.of("read"), apiClient.calls.getFirst().toolNames());
    }

    @Test
    void usesSystemPromptInsteadOfDescription() {
        CapturingApiClient apiClient = completedApiClient();
        AgentRunner runner = new AgentRunner(registry("read"), apiClient, PermissionGate.permissive());
        AgentDefinition definition = new AgentDefinition(
                "test",
                "UNIQUE_DESCRIPTION_SHOULD_NOT_APPEAR",
                "when",
                "UNIQUE_SYSTEM_PROMPT_SHOULD_APPEAR",
                Set.of("read"),
                Set.of(),
                5,
                10,
                PermissionMode.ACCEPT_EDITS);

        runner.run(definition, "inspect", context());

        String systemPrompt = apiClient.calls.getFirst().systemPrompt();
        assertTrue(systemPrompt.contains("UNIQUE_SYSTEM_PROMPT_SHOULD_APPEAR"));
        assertFalse(systemPrompt.contains("UNIQUE_DESCRIPTION_SHOULD_NOT_APPEAR"));
    }

    @Test
    void childSessionInheritsParentWorkingDirectoryAndIsIndependent() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode input = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "checking",
                List.of(new ToolCall("toolu_1", "capture", input))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parentSession = new ConversationSession(tempDir);
        ToolUseContext parentContext = new ToolUseContext(tempDir, parentSession);

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect", parentContext);

        assertEquals(tempDir, capture.context.workingDirectory());
        assertNotSame(parentSession, capture.context.session());
    }

    @Test
    void childContextIncrementsDepthAndKeepsWorkingDirectory() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode input = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "checking",
                List.of(new ToolCall("toolu_1", "capture", input))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parentSession = new ConversationSession(tempDir);
        ToolUseContext parentContext = new ToolUseContext(tempDir, parentSession, 1, 5);

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect", parentContext);

        assertEquals(tempDir, capture.context.workingDirectory());
        assertEquals(2, capture.context.depth());
        assertEquals(5, capture.context.maxDepth());
        assertNotSame(parentSession, capture.context.session());
    }

    @Test
    void maxToolCallsLimitsChildToolCalls() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode input = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "many calls",
                List.of(
                        new ToolCall("toolu_1", "capture", input),
                        new ToolCall("toolu_2", "capture", input),
                        new ToolCall("toolu_3", "capture", input))));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CapturingTool("capture"));
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        AgentDefinition definition = new AgentDefinition(
                "test", "desc", "when", "prompt",
                Set.of("capture"), Set.of(), 15, 2, PermissionMode.ACCEPT_EDITS);

        TurnResult result = runner.run(definition, "inspect", context());

        assertEquals(FinishReason.MAX_TOOL_CALLS, result.finishReason());
        assertTrue(result.finalText().contains("Reached max tool calls: 2"));
        assertEquals(1, apiClient.calls.size());
    }

    @Test
    void usesDefinitionMaxIterationsForChildQueryEngine() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode input = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "loop",
                List.of(new ToolCall("toolu_1", "capture", input))));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CapturingTool("capture"));
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());

        TurnResult result = runner.run(definition(Set.of("capture"), Set.of(), 1), "inspect", context());

        assertEquals(1, apiClient.calls.size());
        assertEquals(FinishReason.MAX_ITERATIONS, result.finishReason());
        assertTrue(result.finalText().contains("Reached max iterations: 1"));
    }

    @Test
    void childSessionInheritsParentPlanMode() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        parent.setPlanMode(true);

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                new ToolUseContext(tempDir, parent));

        assertTrue(capture.context.session().isPlanMode(),
                "child session should inherit parent plan mode");
    }

    @Test
    void parentBypassOverridesAgentDefinedAcceptEdits() {
        // Invariant: child never gets a stricter permission mode than parent.
        // Parent BYPASS, agent definition ACCEPT_EDITS → child inherits BYPASS.
        CapturingTool capture = runCapturingChild(
                PermissionMode.BYPASS,
                PermissionMode.ACCEPT_EDITS);

        assertEquals(PermissionMode.BYPASS, capture.context.session().permissionMode(),
                "parent BYPASS must not be downgraded by agent definition");
    }

    @Test
    void parentAcceptEditsOverridesAgentDefinedDefault() {
        // Invariant: child never gets a stricter permission mode than parent.
        // Parent ACCEPT_EDITS, agent definition DEFAULT → child inherits ACCEPT_EDITS.
        CapturingTool capture = runCapturingChild(
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.DEFAULT);

        assertEquals(PermissionMode.ACCEPT_EDITS, capture.context.session().permissionMode(),
                "parent ACCEPT_EDITS must not be downgraded by agent definition");
    }

    @Test
    void parentDefaultLetsAgentDefinedAcceptEditsThrough() {
        // When parent is the strictest mode, the agent's own declared mode wins.
        CapturingTool capture = runCapturingChild(
                PermissionMode.DEFAULT,
                PermissionMode.ACCEPT_EDITS);

        assertEquals(PermissionMode.ACCEPT_EDITS, capture.context.session().permissionMode(),
                "parent DEFAULT should let agent's declared ACCEPT_EDITS win");
    }

    @Test
    void agentCanDeclareMorePermissiveModeThanParent() {
        // An agent definition can be MORE permissive than the parent (it adopts
        // the agent's mode), but never less permissive (covered by the two
        // tests above).
        CapturingTool capture = runCapturingChild(
                PermissionMode.DEFAULT,
                PermissionMode.BYPASS);

        assertEquals(PermissionMode.BYPASS, capture.context.session().permissionMode(),
                "agent definition may upgrade child mode beyond parent");
    }

    /** Spawns a sub-agent with the given parent and definition modes, captures
     *  the child session via a CapturingTool, and returns it for inspection. */
    private CapturingTool runCapturingChild(PermissionMode parentMode, PermissionMode definitionMode) {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());

        ConversationSession parent = new ConversationSession(tempDir);
        parent.setPermissionMode(parentMode);

        AgentDefinition definition = new AgentDefinition(
                "test", "description", "when", "system prompt",
                Set.of("capture"), Set.of(),
                5, 10,
                definitionMode);

        runner.run(definition, "inspect", new ToolUseContext(tempDir, parent));
        return capture;
    }

    @Test
    void forwardsChildTokenReportToParentAndAccumulates() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        RecordingListener parentListener = new RecordingListener();
        parent.addListener(parentListener);

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                new ToolUseContext(tempDir, parent));

        ConversationSession child = capture.context.session();
        child.fireMetaEvent(new MetaEvent.TokenReport(
                new TokenUsage(10, 20, 0, 0), 100, 200));

        assertTrue(parentListener.metaEvents.stream()
                        .anyMatch(e -> e instanceof MetaEvent.TokenReport),
                "parent should receive forwarded TokenReport");
        assertEquals(30, parent.tokenUsage().total(),
                "parent token usage should accumulate forwarded child report");
    }

    @Test
    void doesNotForwardChildErrorToParent() {
        // Regression guard: TurnRenderer.onMetaEvent reacts to MetaEvent.Error
        // by aborting the current turn. If a child agent's terminal Error
        // (MAX_ITERATIONS / API_ERROR / CANCELLED) were forwarded, the parent
        // turn would abort mid-AgentTool invocation. Sub-agent failures must
        // surface only through the failing ToolResult.
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        RecordingListener parentListener = new RecordingListener();
        parent.addListener(parentListener);

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                new ToolUseContext(tempDir, parent));

        ConversationSession child = capture.context.session();
        child.fireMetaEvent(new MetaEvent.Error("boom", FinishReason.API_ERROR));

        assertTrue(parentListener.metaEvents.stream()
                        .noneMatch(e -> e instanceof MetaEvent.Error),
                "child Error must NOT leak to parent (would trigger parent turn abort)");
    }

    @Test
    void doesNotForwardChildPlanOrCompactEventsToParent() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        RecordingListener parentListener = new RecordingListener();
        parent.addListener(parentListener);

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                new ToolUseContext(tempDir, parent));

        ConversationSession child = capture.context.session();
        child.fireMetaEvent(new MetaEvent.CompactStarted(1_000, 800));
        child.fireMetaEvent(new MetaEvent.PlanModeEntered());

        assertTrue(parentListener.metaEvents.stream()
                        .noneMatch(e -> e instanceof MetaEvent.CompactStarted
                                || e instanceof MetaEvent.PlanModeEntered),
                "child plan/compact events should not leak to parent");
    }

    @Test
    void doesNotForwardChildMessageAppendsToParent() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        RecordingListener parentListener = new RecordingListener();
        parent.addListener(parentListener);
        int beforeAppends = parentListener.messageAppends;

        runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                new ToolUseContext(tempDir, parent));

        assertEquals(beforeAppends, parentListener.messageAppends,
                "child message appends must not surface on the parent listener");
    }

    @Test
    void childAllowsNonReadOnlyToolsInsideAllowlist() {
        // Child shares parent's permissive gate in tests, so non-readonly
        // tools inside the allowlist can run.
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "writer", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        WritableCapturingTool writer = new WritableCapturingTool("writer");
        ToolRegistry registry = new ToolRegistry();
        registry.register(writer);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());

        TurnResult result = runner.run(definition(Set.of("writer"), Set.of(), 5),
                "inspect", context());

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertTrue(writer.invoked, "non-readonly tool inside allowlist should run");
    }

    @Test
    void forwardsChildToolStartProjectionToParentButNotChildProgress() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        RecordingListener parentListener = new RecordingListener();
        parent.addListener(parentListener);

        ToolExecutor.CURRENT_TOOL_USE_ID.set("parent-tool");
        try {
            runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                    new ToolUseContext(tempDir, parent));
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        ConversationSession child = capture.context.session();
        ObjectNode input = mapper.createObjectNode().put("command", "pwd");
        child.fireToolExecutionStarted("child-tool", "bash", input);
        child.fireToolExecutionProgress("child-tool", "▸ secret");
        child.fireToolExecutionProgress("child-tool", "plain stdout");

        assertTrue(parentListener.activityEvents.stream().anyMatch(p -> p.contains("Running pwd")),
                parentListener.activityEvents.toString());
        assertTrue(parentListener.progressEvents.stream().noneMatch("plain stdout"::equals),
                parentListener.progressEvents.toString());
        assertTrue(parentListener.progressEvents.stream().noneMatch("▸ secret"::equals),
                parentListener.progressEvents.toString());
        assertTrue(parentListener.activityEvents.stream().noneMatch("plain stdout"::equals),
                parentListener.activityEvents.toString());
        assertTrue(parentListener.activityEvents.stream().noneMatch("▸ secret"::equals),
                parentListener.activityEvents.toString());
    }

    @Test
    void forwardsGrandchildActivityProjectionWithoutForwardingRawProgress() {
        CapturingApiClient apiClient = new CapturingApiClient();
        ObjectNode emptyInput = mapper.createObjectNode();
        apiClient.enqueue(new ApiClient.ApiResponse(
                "calling",
                List.of(new ToolCall("toolu_1", "capture", emptyInput))));
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        CapturingTool capture = new CapturingTool("capture");
        ToolRegistry registry = new ToolRegistry();
        registry.register(capture);
        AgentRunner runner = new AgentRunner(registry, apiClient, PermissionGate.permissive());
        ConversationSession parent = new ConversationSession(tempDir);
        RecordingListener parentListener = new RecordingListener();
        parent.addListener(parentListener);

        ToolExecutor.CURRENT_TOOL_USE_ID.set("parent-tool");
        try {
            runner.run(definition(Set.of("capture"), Set.of(), 5), "inspect",
                    new ToolUseContext(tempDir, parent));
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        ConversationSession child = capture.context.session();
        child.fireToolExecutionActivity("child-agent-tool", ToolActivitySummary.asProjectionLine("grep",
                mapper.createObjectNode().put("pattern", "needle")));
        child.fireToolExecutionProgress("child-agent-tool", "▸ not typed activity");

        assertTrue(parentListener.activityEvents.stream().anyMatch(p -> p.contains("Searching for \"needle\"")),
                parentListener.activityEvents.toString());
        assertTrue(parentListener.activityEvents.stream().noneMatch("▸ not typed activity"::equals),
                parentListener.activityEvents.toString());
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, new ConversationSession(tempDir));
    }

    private AgentDefinition definition(Set<String> allowedTools, Set<String> disallowedTools, int maxIterations) {
        return new AgentDefinition(
                "test",
                "description",
                "when",
                "system prompt",
                allowedTools,
                disallowedTools,
                maxIterations,
                10,
                PermissionMode.ACCEPT_EDITS);
    }

    private ToolRegistry registry(String... names) {
        ToolRegistry registry = new ToolRegistry();
        for (String name : names) {
            registry.register(new CapturingTool(name));
        }
        return registry;
    }

    private CapturingApiClient completedApiClient() {
        CapturingApiClient apiClient = new CapturingApiClient();
        apiClient.enqueue(new ApiClient.ApiResponse("done", List.of()));
        return apiClient;
    }

    private static final class CapturingApiClient implements ApiClient {

        private final Queue<ApiResponse> responses = new ArrayDeque<>();
        private final List<ApiCall> calls = new java.util.ArrayList<>();

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
            calls.add(new ApiCall(
                    List.copyOf(messages),
                    systemPrompt,
                    tools.stream().map(Tool::name).toList()));
            return responses.isEmpty() ? new ApiResponse("done", List.of()) : responses.remove();
        }
    }

    private record ApiCall(List<Message> messages, String systemPrompt, List<String> toolNames) {
    }

    private final class CapturingTool implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }

        private final String name;
        private ToolUseContext context;

        CapturingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test tool";
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
            schema.putArray("required");
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            this.context = context;
            return new ToolResult(name(), true, "captured");
        }
    }

    private static final class RecordingListener implements SessionListener {
        final List<MetaEvent> metaEvents = new java.util.ArrayList<>();
        final List<String> progressEvents = new java.util.ArrayList<>();
        final List<String> activityEvents = new java.util.ArrayList<>();
        int messageAppends;

        @Override
        public void onMetaEvent(MetaEvent meta) {
            metaEvents.add(meta);
        }

        @Override
        public void onMessageAppended(int index, Message message) {
            messageAppends++;
        }

        @Override
        public void onToolExecutionProgress(String toolUseId, String progressText) {
            progressEvents.add(progressText);
        }

        @Override
        public void onToolExecutionActivity(String toolUseId, String activityText) {
            activityEvents.add(activityText);
        }
    }

    private static final class WritableCapturingTool implements Tool<ObjectNode> {

        private final String name;
        boolean invoked;

        WritableCapturingTool(String name) {
            this.name = name;
        }

        @Override
        public Class<ObjectNode> inputType() { return ObjectNode.class; }

        @Override
        public String name() { return name; }

        @Override
        public String description() { return "non-readonly test tool"; }

        @Override
        public boolean isReadOnly() { return false; }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            schema.putArray("required");
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            invoked = true;
            return new ToolResult(name(), true, "wrote");
        }
    }
}
