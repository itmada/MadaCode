package madacode.agent;

import madacode.services.api.ApiClient;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.engine.ToolUseContext;
import madacode.core.turn.TurnResult;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolNameCanonicalizer;
import madacode.tool.ToolRegistry;
import madacode.core.engine.ToolExecutor;
import madacode.tool.access.ToolAccessResolver;
import madacode.tool.access.ToolCapabilityProfile;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs a sub-agent with an independent message context.
 *
 * <p>Sub-agent capabilities are attached to the child {@link ToolUseContext}
 * as a {@link ToolCapabilityProfile}. The child keeps the full tool directory for
 * name resolution and {@code tool_search}, while the tool access layer decides
 * prompt visibility, search loadability, and execution callability from that
 * profile. The child shares the parent's {@link PermissionGate} so child tool
 * approvals surface to the user's terminal.
 *
 * <p>Plan mode propagates from parent to child: if the parent session is
 * in plan mode, the child is too. Writes inside the sub-agent are then
 * blocked by the same plan-mode check that protects the parent.
 *
 * <p>The child also inherits the parent's {@code permissionMode} verbatim:
 * spawning a sub-agent never escalates privileges beyond what the user
 * granted the parent session. Capabilities are narrowed by tool allowlists
 * ({@code allowedTools}/{@code disallowedTools}), not by widening the
 * permission mode.
 *
 * <p>A {@link ParentEventForwarder} bridges sub-agent events to the parent
 * session: token usage bubbles up for billing correctness, and child tool
 * starts are projected as lightweight parent-card activity summaries.
 * Messages, streaming, raw tool progress/stdout, tool completion payloads,
 * and child error meta-events stay inside the child to keep the parent UI
 * invariants intact.
 */
public class AgentRunner {

    private final ToolRegistry fullRegistry;
    private final ApiClient apiClient;
    private final PermissionGate parentGate;
    private final ToolAccessResolver toolAccessResolver;

    public AgentRunner(ToolRegistry fullRegistry, ApiClient apiClient, PermissionGate parentGate) {
        this(fullRegistry, apiClient, parentGate, ToolAccessResolver.defaultResolver());
    }

    public AgentRunner(
            ToolRegistry fullRegistry,
            ApiClient apiClient,
            PermissionGate parentGate,
            ToolAccessResolver toolAccessResolver) {
        this.fullRegistry = fullRegistry;
        this.apiClient = apiClient;
        this.parentGate = Objects.requireNonNull(parentGate, "parentGate");
        this.toolAccessResolver = Objects.requireNonNull(toolAccessResolver, "toolAccessResolver");
    }

    public TurnResult run(AgentDefinition definition, String input, ToolUseContext parentContext) {
        SystemPromptBuilder childPromptBuilder = SystemPromptBuilder.builder()
                .agentContext(definition.systemPrompt())
                .build();

        QueryEngine.Builder engineBuilder = QueryEngine.builder(
                        apiClient, fullRegistry, childPromptBuilder, parentGate)
                .toolAccessResolver(toolAccessResolver);
        if (definition.maxIterations() != null) {
            engineBuilder.maxIterations(definition.maxIterations());
        }
        QueryEngine childEngine = engineBuilder.build();

        ConversationSession parentSession = parentContext.session();
        ConversationSession childSession = new ConversationSession(parentContext.workingDirectory());
        childSession.setPlanMode(parentSession.isPlanMode());

        // Sub-agents inherit the parent session's permission mode. Spawning a
        // sub-agent must never escalate privileges beyond what the user granted
        // the parent: a parent in DEFAULT means the child also prompts before
        // edits (the shared gate surfaces approvals to the user), while a parent
        // in BYPASS propagates down so autonomous runs stay prompt-free.
        childSession.setPermissionMode(parentSession.permissionMode());

        String parentToolUseId = ToolExecutor.CURRENT_TOOL_USE_ID.get();
        childSession.addListener(new ParentEventForwarder(parentSession, parentToolUseId));

        // Make the child session visible to any out-of-band observer the parent
        // carries (e.g. the eval trace collector), propagating the observer down so
        // the whole sub-agent tree is observed. No-op in production.
        parentSession.registerSubAgent(childSession);

        Set<String> allowedTools = canonicalToolNames(definition.allowedTools());
        Set<String> disallowedTools = canonicalToolNames(definition.disallowedTools());
        ToolCapabilityProfile childProfile = !definition.allowedToolsSpecified()
                ? ToolCapabilityProfile.subAgentUnrestricted(definition.agentType(), disallowedTools)
                : ToolCapabilityProfile.subAgentRestrictedAllowList(
                        definition.agentType(), allowedTools, disallowedTools);
        ToolUseContext childContext = parentContext.childContext(childSession, childProfile);

        return childEngine.runTurn(childSession, input, childContext);
    }

    private Set<String> canonicalToolNames(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        return names.stream()
                .map(this::canonicalToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String canonicalToolName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return fullRegistry.find(name)
                .map(Tool::name)
                .orElse(ToolNameCanonicalizer.canonicalize(name));
    }
}
