package madacode.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.agent.BuiltInAgentLoader;
import madacode.core.model.MetaEvent;
import madacode.core.model.ToolCall;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionListener;
import madacode.core.session.SessionMode;
import madacode.core.turn.CancellationException;
import madacode.hook.HookManager;
import madacode.permission.PermissionDecision;
import madacode.permission.PermissionGate;
import madacode.services.api.ApiClient;
import madacode.tool.AgentTool;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.ToolVisibility;
import madacode.tool.VisibleTools;
import madacode.tool.access.AgentToolProfile;
import madacode.tool.access.ToolAccessResolver;
import madacode.tool.validation.ToolInputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void successFiresReachedBeforePermissionStartedExecutionResultAndCompleted() {
        RecordingListener listener = new RecordingListener();
        RecordingPermissionGate permissionGate = new RecordingPermissionGate(listener.events, PermissionDecision.allow());
        RecordingTool tool = RecordingTool.succeeding("capture", listener.events);

        ToolResult result = executorFor(tool, permissionGate, null).execute(
                call(tool.name(), validInput("hello")),
                context(listener, tool));

        assertTrue(result.success());
        assertEquals("received hello", result.output());
        assertEquals(List.of("reached", "permission", "started", "execute", "result", "completed"),
                listener.events);
    }

    @Test
    void unknownToolReturnsFailedResultWithoutMetaErrorAndCompletes() {
        RecordingListener listener = new RecordingListener();

        ToolResult result = executorFor(null, PermissionGate.permissive(), null).execute(
                call("missing_tool", validInput("hello")),
                context(listener));

        assertFalse(result.success());
        assertTrue(result.output().contains("unknown tool \"missing_tool\""));
        assertFalse(listener.events.contains("meta-error"));
        assertTrue(listener.events.contains("completed"));
        assertEquals(List.of("result", "completed"), listener.events);
    }

    @Test
    void directExecutorRejectsToolLoadedAfterRequestButNotExposedInSnapshot() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.succeeding("capture", listener.events);

        ToolUseContext context = context(listener, tool).withExposedTools(ToolVisibility.empty());
        ToolResult result = executorFor(tool, PermissionGate.permissive(), null).execute(
                call(tool.name(), validInput("hello")),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("not exposed"));
        assertEquals(List.of("result", "completed"), listener.events);
        assertTrue(context.session().loadedDeferredTools().contains(tool.name()));
    }

    @Test
    void childContextInheritsLoadedToolOverlayWithoutMutatingChildSession() {
        RecordingListener listener = new RecordingListener();
        RecordingTool inherited = RecordingTool.succeeding("inherited_tool", listener.events);
        RecordingTool explicitlyAllowed = RecordingTool.succeeding("allowed_tool", listener.events);
        RecordingTool outsideProfile = RecordingTool.succeeding("outside_profile", listener.events);

        ConversationSession parentSession = session(listener, inherited);
        ToolUseContext parentContext = new ToolUseContext(tempDir, parentSession);
        ConversationSession childSession = new ConversationSession(tempDir);
        ToolUseContext childContext = parentContext.childContext(
                childSession,
                new AgentToolProfile(
                        "child",
                        Set.of(inherited.name(), explicitlyAllowed.name()),
                        Set.of(),
                        true));

        VisibleTools visibleTools = ToolAccessResolver.defaultResolver().visibleTools(
                List.of(inherited, explicitlyAllowed, outsideProfile),
                childContext.toolAccessScope());

        assertEquals(Set.of(inherited.name(), explicitlyAllowed.name()), visibleTools.names());
        assertTrue(parentSession.loadedDeferredTools().contains(inherited.name()));
        assertTrue(childSession.loadedDeferredTools().isEmpty());
        assertFalse(ToolAccessResolver.defaultResolver()
                .decideForToolSearch(outsideProfile, childContext.toolAccessScope())
                .loadableBySearch());
    }

    @Test
    void longRunningWorkerCapabilitySetAlsoRestrictsToolSearchLoadability() {
        RecordingListener listener = new RecordingListener();
        RecordingTool workerTool = RecordingTool.succeeding("file_read", listener.events);
        RecordingTool outsideWorkerSet = RecordingTool.succeeding("web_fetch", listener.events);
        ConversationSession workerSession = new ConversationSession(tempDir);
        workerSession.setWorkflowMode(SessionMode.LONG_RUNNING);
        workerSession.setLongRunningStage(LongRunningStage.RUNNING);
        workerSession.setLongRunningWorkerSession(true);
        ToolUseContext workerContext = new ToolUseContext(tempDir, workerSession);

        ToolAccessResolver resolver = ToolAccessResolver.defaultResolver();
        VisibleTools visibleTools = resolver.visibleTools(
                List.of(workerTool, outsideWorkerSet),
                workerContext.toolAccessScope());

        assertEquals(Set.of(workerTool.name()), visibleTools.names());
        assertFalse(resolver.decideForToolSearch(outsideWorkerSet, workerContext.toolAccessScope())
                .loadableBySearch());
    }

    @Test
    void validationFailureReturnsFailedResultAndCompletes() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.succeeding("capture", listener.events);

        ToolResult result = executorFor(tool, PermissionGate.permissive(), null).execute(
                call(tool.name(), mapper.createObjectNode()),
                context(listener, tool));

        assertFalse(result.success());
        assertTrue(result.output().startsWith("Invalid tool input for capture:"));
        assertTrue(listener.events.contains("completed"));
        assertEquals(List.of("result", "completed"), listener.events);
    }

    @Test
    void permissionDenialReturnsFailedResultAndCompletes() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.succeeding("capture", listener.events);

        ToolResult result = executorFor(
                        tool,
                        new RecordingPermissionGate(listener.events, PermissionDecision.deny("nope")),
                        null)
                .execute(call(tool.name(), validInput("hello")), context(listener, tool));

        assertFalse(result.success());
        assertEquals("Permission denied: nope", result.output());
        assertEquals(List.of("reached", "permission", "result", "completed"), listener.events);
    }

    @Test
    void toolExceptionReturnsFailedResultAndCompletes() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.throwing("capture", listener.events, new IllegalStateException("boom"));

        ToolResult result = executorFor(tool, PermissionGate.permissive(), null).execute(
                call(tool.name(), validInput("hello")),
                context(listener, tool));

        assertFalse(result.success());
        assertEquals("Tool execution failed: boom", result.output());
        assertEquals(List.of("reached", "started", "execute", "result", "completed"), listener.events);
    }

    @Test
    void cancellationExceptionReturnsFailedResultAndCompletes() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.throwing("capture", listener.events, new CancellationException("stopped"));

        ToolResult result = executorFor(tool, PermissionGate.permissive(), null).execute(
                call(tool.name(), validInput("hello")),
                context(listener, tool));

        assertFalse(result.success());
        assertEquals("Cancelled: stopped", result.output());
        assertEquals(List.of("reached", "started", "execute", "result", "completed"), listener.events);
    }

    @Test
    void planModeRejectsNonReadOnlyToolThatIsNotAllowedByName() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.succeeding("mutating_tool", listener.events, false);
        ConversationSession session = session(listener, tool);
        session.setPlanMode(true);

        ToolResult result = executorFor(tool, PermissionGate.permissive(), null).execute(
                call(tool.name(), validInput("hello")),
                new ToolUseContext(tempDir, session));

        assertFalse(result.success());
        assertTrue(result.output().contains("Plan mode active"));
        assertEquals(List.of("result", "completed"), listener.events);
    }

    @Test
    void onlyToolsDeclaringHookBypassSkipPreAndPostHooks() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.succeeding("agent", listener.events);
        RecordingHookManager hookManager = new RecordingHookManager(tempDir.resolve("hooks.json"));

        ToolResult result = executorFor(tool, PermissionGate.permissive(), hookManager).execute(
                call(tool.name(), validInput("hello")),
                context(listener, tool));

        assertTrue(result.success());
        assertTrue(hookManager.preHookRan);
        assertTrue(hookManager.postHookRan);
        assertEquals(List.of("reached", "started", "execute", "result", "completed"), listener.events);
        assertTrue(realAgentTool().bypassesHooks());
    }

    @Test
    void hookBypassingToolSkipsPreAndPostHooks() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.hookBypassing("capture", listener.events);
        RecordingHookManager hookManager = new RecordingHookManager(tempDir.resolve("hooks.json"));

        ToolResult result = executorFor(tool, PermissionGate.permissive(), hookManager).execute(
                call(tool.name(), validInput("hello")),
                context(listener, tool));

        assertTrue(result.success());
        assertFalse(hookManager.preHookRan);
        assertFalse(hookManager.postHookRan);
        assertEquals(List.of("reached", "started", "execute", "result", "completed"), listener.events);
    }

    @Test
    void preHookRewrittenInputIsRecoercedBeforeExecution() {
        RecordingListener listener = new RecordingListener();
        RecordingTool tool = RecordingTool.succeeding("capture", listener.events);
        ObjectNode rewrittenInput = validInput("rewritten");
        RecordingHookManager hookManager = new RecordingHookManager(tempDir.resolve("hooks.json"), rewrittenInput);

        ToolResult result = executorFor(tool, PermissionGate.permissive(), hookManager).execute(
                call(tool.name(), validInput("original")),
                context(listener, tool));

        assertTrue(result.success());
        assertEquals("received rewritten", result.output());
        assertTrue(hookManager.preHookRan);
        assertTrue(hookManager.postHookRan);
        assertEquals(List.of("reached", "started", "execute", "result", "completed"), listener.events);
    }

    private ToolExecutor executorFor(Tool<?> tool, PermissionGate permissionGate, HookManager hookManager) {
        ToolRegistry registry = new ToolRegistry();
        if (tool != null) {
            registry.register(tool);
        }
        return new ToolExecutor(registry, new ToolInputValidator(), permissionGate, hookManager, mapper);
    }

    private ToolUseContext context(RecordingListener listener, Tool<?> tool) {
        return new ToolUseContext(tempDir, session(listener, tool));
    }

    private ToolUseContext context(RecordingListener listener) {
        ConversationSession session = new ConversationSession(tempDir);
        session.addListener(listener);
        return new ToolUseContext(tempDir, session);
    }

    private ConversationSession session(RecordingListener listener, Tool<?> tool) {
        ConversationSession session = new ConversationSession(tempDir);
        session.addListener(listener);
        session.loadDeferredTool(tool.name());
        return session;
    }

    private ToolCall call(String toolName, ObjectNode input) {
        return new ToolCall("toolu_1", toolName, input);
    }

    private AgentTool realAgentTool() {
        ApiClient fakeApiClient = (messages, systemPrompt, tools, sink, cancellationToken) ->
                new ApiClient.ApiResponse("", List.of());
        return new AgentTool(
                new AgentRunner(new ToolRegistry(), fakeApiClient, PermissionGate.permissive()),
                AgentRegistry.loaded(new BuiltInAgentLoader()));
    }

    private ObjectNode validInput(String value) {
        ObjectNode input = mapper.createObjectNode();
        input.put("value", value);
        return input;
    }

    private static final class RecordingListener implements SessionListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onToolExecutionReached(String toolUseId, String toolName, ObjectNode input) {
            events.add("reached");
        }

        @Override
        public void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
            events.add("started");
        }

        @Override
        public void onToolResultAvailable(String toolUseId, boolean success, String output) {
            events.add("result");
        }

        @Override
        public void onToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
            events.add("completed");
        }

        @Override
        public void onMetaEvent(MetaEvent meta) {
            if (meta instanceof MetaEvent.Error) {
                events.add("meta-error");
            }
        }
    }

    private record RecordingPermissionGate(List<String> events, PermissionDecision decision)
            implements PermissionGate {
        @Override
        public PermissionDecision check(Tool<?> tool, ObjectNode input, ToolUseContext context) {
            events.add("permission");
            return decision;
        }
    }

    private static final class RecordingHookManager extends HookManager {
        private boolean preHookRan;
        private boolean postHookRan;
        private final ObjectNode rewrittenInput;

        private RecordingHookManager(Path configPath) {
            this(configPath, null);
        }

        private RecordingHookManager(Path configPath, ObjectNode rewrittenInput) {
            super(configPath);
            this.rewrittenInput = rewrittenInput;
        }

        @Override
        public PreToolUseResult runPreToolUse(String toolName, ObjectNode toolInput, String sessionId) {
            preHookRan = true;
            return new PreToolUseResult(true, null, rewrittenInput == null ? toolInput : rewrittenInput);
        }

        @Override
        public void runPostToolUse(
                String toolName, ObjectNode toolInput, String sessionId, boolean success, String output) {
            postHookRan = true;
        }
    }

    private static final class RecordingTool implements Tool<ToolInput> {
        private final String name;
        private final List<String> events;
        private final boolean readOnly;
        private final boolean bypassesHooks;
        private final RuntimeException thrown;

        private RecordingTool(
                String name,
                List<String> events,
                boolean readOnly,
                boolean bypassesHooks,
                RuntimeException thrown) {
            this.name = name;
            this.events = events;
            this.readOnly = readOnly;
            this.bypassesHooks = bypassesHooks;
            this.thrown = thrown;
        }

        private static RecordingTool succeeding(String name, List<String> events) {
            return succeeding(name, events, true);
        }

        private static RecordingTool succeeding(String name, List<String> events, boolean readOnly) {
            return new RecordingTool(name, events, readOnly, false, null);
        }

        private static RecordingTool hookBypassing(String name, List<String> events) {
            return new RecordingTool(name, events, true, true, null);
        }

        private static RecordingTool throwing(String name, List<String> events, RuntimeException thrown) {
            return new RecordingTool(name, events, true, false, thrown);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Records execution.";
        }

        @Override
        public Class<ToolInput> inputType() {
            return ToolInput.class;
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public boolean bypassesHooks() {
            return bypassesHooks;
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
        public ToolResult execute(ToolInput input, ToolUseContext context) {
            events.add("execute");
            if (thrown != null) {
                throw thrown;
            }
            return new ToolResult(name, true, "received " + input.value());
        }
    }

    private record ToolInput(String value) {
    }
}
