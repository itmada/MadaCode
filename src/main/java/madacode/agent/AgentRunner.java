package madacode.agent;

import madacode.services.api.ApiClient;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.engine.ToolUseContext;
import madacode.core.turn.TurnResult;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionGate;
import madacode.permission.PermissionMode;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.core.engine.ToolExecutor;
import madacode.util.ToolNameNormalizer;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs a sub-agent with an independent message context.
 *
 * <p>Single source of truth for sub-agent capabilities: the child
 * {@link ToolRegistry} is filtered by {@code allowedTools}/{@code disallowedTools}
 * (with {@code agent} always excluded to prevent recursion). The child
 * shares the parent's {@link PermissionGate} so child tool approvals
 * surface to the user's terminal.
 *
 * <p>Plan mode propagates from parent to child: if the parent session is
 * in plan mode, the child is too. Writes inside the sub-agent are then
 * blocked by the same plan-mode check that protects the parent.
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

    public AgentRunner(ToolRegistry fullRegistry, ApiClient apiClient, PermissionGate parentGate) {
        this.fullRegistry = fullRegistry;
        this.apiClient = apiClient;
        this.parentGate = Objects.requireNonNull(parentGate, "parentGate");
    }

    public TurnResult run(AgentDefinition definition, String input, ToolUseContext parentContext) {
        ToolRegistry childRegistry = buildChildRegistry(definition);

        SystemPromptBuilder childPromptBuilder = SystemPromptBuilder.builder()
                .agentContext(definition.systemPrompt())
                .build();

        QueryEngine.Builder engineBuilder = QueryEngine.builder(
                apiClient, childRegistry, childPromptBuilder, parentGate);
        if (definition.maxIterations() != null) {
            engineBuilder.maxIterations(definition.maxIterations());
        }
        QueryEngine childEngine = engineBuilder.build();

        ConversationSession parentSession = parentContext.session();
        ConversationSession childSession = new ConversationSession(parentContext.workingDirectory());
        childSession.setPlanMode(parentSession.isPlanMode());
        childSession.loadDeferredTools(parentSession.loadedDeferredTools());
        childSession.loadDeferredTools(canonicalToolNames(definition.allowedTools()));

        PermissionMode childMode = resolveChildMode(
                definition.permissionMode(),
                parentSession.permissionMode());
        childSession.setPermissionMode(childMode);

        String parentToolUseId = ToolExecutor.CURRENT_TOOL_USE_ID.get();
        childSession.addListener(new ParentEventForwarder(parentSession, parentToolUseId));

        ToolUseContext childContext = parentContext.childContext(childSession);

        return childEngine.runTurn(childSession, input, childContext);
    }

    /**
     * Resolves the sub-agent's permission mode using the "never downgrade
     * parent's permissiveness" rule: if the parent is already at least as
     * permissive as the agent's declared mode, inherit the parent's mode;
     * otherwise the agent's own mode (defaulting to ACCEPT_EDITS) wins.
     */
    private static PermissionMode resolveChildMode(PermissionMode defMode, PermissionMode parentMode) {
        PermissionMode effective = defMode != null ? defMode : PermissionMode.ACCEPT_EDITS;
        if (parentMode.isAtLeastAsPermissiveAs(effective)) {
            return parentMode;
        }
        return effective;
    }

    private ToolRegistry buildChildRegistry(AgentDefinition definition) {
        ToolRegistry childRegistry = new ToolRegistry();
        Set<String> allowedTools = canonicalToolNames(definition.allowedTools());
        Set<String> disallowedTools = canonicalToolNames(definition.disallowedTools());
        fullRegistry.tools().stream()
                .filter(t -> {
                    if ("agent".equals(t.name())) {
                        return false;
                    }
                    if (disallowedTools.contains(t.name())) {
                        return false;
                    }
                    if (!allowedTools.isEmpty()) {
                        return allowedTools.contains(t.name());
                    }
                    return true;
                })
                .forEach(childRegistry::register);
        return childRegistry;
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
                .orElse(ToolNameNormalizer.normalize(name));
    }
}
