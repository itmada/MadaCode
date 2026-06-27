package madacode.tool.access;

import madacode.core.session.ConversationSession;
import madacode.longrunning.LongRunningCapabilityPolicy;
import madacode.tool.Tool;
import madacode.tool.VisibleTools;

import java.util.Collection;
import java.util.List;

/**
 * Single entry point for deciding whether a tool is exposed, loadable, or callable
 * in the current role/session/request context.
 *
 * <p>Resolution is one straight line: a session or sub-agent's
 * {@link ToolCapabilityProfile} decides
 * whether the tool may ever be used; a {@link WorkflowCapabilityPolicy} overlay gates
 * workflow lifecycle tools by stage; then the exposure tier (core vs deferred) and
 * deferred-load state decide whether it is declared now or must be loaded first.
 */
public final class ToolAccessResolver {

    private static final ToolAccessResolver DEFAULT =
            new ToolAccessResolver(defaultWorkflowPolicy());

    private final WorkflowCapabilityPolicy workflowPolicy;

    public ToolAccessResolver(WorkflowCapabilityPolicy workflowPolicy) {
        this.workflowPolicy = workflowPolicy == null ? WorkflowCapabilityPolicy.none() : workflowPolicy;
    }

    public static ToolAccessResolver defaultResolver() {
        return DEFAULT;
    }

    public static WorkflowCapabilityPolicy defaultWorkflowPolicy() {
        return CompositeWorkflowCapabilityPolicy.of(new LongRunningCapabilityPolicy());
    }

    // ---- Visibility ------------------------------------------------------

    public VisibleTools visibleTools(Collection<Tool<?>> tools, ConversationSession session) {
        return visibleTools(tools, ToolAccessScope.forSession(session));
    }

    public VisibleTools visibleTools(Collection<Tool<?>> tools, ToolAccessScope scope) {
        Collection<Tool<?>> safeTools = tools == null ? List.of() : tools;
        ToolAccessScope safeScope = scope(scope);
        return new VisibleTools(safeTools.stream()
                .filter(tool -> evaluate(tool, safeScope).exposed())
                .toList());
    }

    /**
     * Core resolution. Every other method is a reading of this single decision.
     */
    public ToolAccessDecision evaluate(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return ToolAccessDecision.denied(null, "Unknown tool.");
        }
        ToolAccessScope safeScope = scope(scope);
        ConversationSession session = safeScope.session();
        String name = tool.name();

        ToolCapabilityProfile profile = effectiveProfile(safeScope);
        if (!profile.allows(name)) {
            return ToolAccessDecision.denied(name, "Tool is not part of the current agent capability set.");
        }
        if (session != null && session.isPlanMode() && !tool.isPlanModeSafe()) {
            return ToolAccessDecision.denied(
                    name,
                    "Plan mode active — only read tools are available. "
                            + "The host must exit plan mode before implementation tools are available.");
        }
        // Capability floor: a sub-agent's explicit profile narrows, never widens, the
        // session's workflow restriction. When both are present the effective
        // capability is their intersection — a child of a restricted session can use
        // only what both it and the workflow allow.
        ToolCapabilityProfile workflowFloor = sessionProfile(session);
        if (workflowFloor != null && workflowFloor != profile && !workflowFloor.allows(name)) {
            return ToolAccessDecision.denied(name, "Tool is restricted by the active workflow for this session.");
        }

        WorkflowVote vote = workflowPolicy.lifecycleVote(tool, session);
        if (vote.kind() == WorkflowVote.Kind.DENY) {
            return ToolAccessDecision.denied(name, vote.reason());
        }
        if (vote.kind() == WorkflowVote.Kind.EXPOSE) {
            return ToolAccessDecision.exposed(name);
        }

        if (directlyExposed(profile, name)) {
            return ToolAccessDecision.exposed(name);
        }
        if (safeScope.isDeferredToolLoaded(name)) {
            return ToolAccessDecision.exposed(name);
        }
        return ToolAccessDecision.loadable(name);
    }

    // ---- tool_search -----------------------------------------------------

    public ToolAccessDecision decideForToolSearch(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return ToolAccessDecision.denied(null, "Unknown tool.");
        }
        ToolAccessDecision decision = evaluate(tool, scope(scope));
        if (decision.denied()) {
            return decision;
        }
        if (decision.exposed()) {
            return ToolAccessDecision.hidden(tool.name(), "already available");
        }
        return ToolAccessDecision.loadable(tool.name());
    }

    // ---- Execution guards ------------------------------------------------

    public String executionDenialReason(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return "Unknown tool.";
        }
        ToolAccessDecision decision = evaluate(tool, scope(scope));
        if (decision.exposed()) {
            return null;
        }
        if (decision.denied()) {
            return decision.reason();
        }
        return "Tool is not loaded in the current session. Use tool_search first; "
                + "loaded tools become callable on the next model request.";
    }

    /**
     * Execution boundary bound to the exact declarations sent with the model
     * request: a tool loaded after that request cannot be called in the same batch.
     */
    public String exposedToolDenialReason(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return "Unknown tool.";
        }
        ToolAccessScope safeScope = scope(scope);
        ToolAccessDecision decision = evaluate(tool, safeScope);
        if (decision.denied()) {
            return decision.reason();
        }
        if (safeScope.hasExposedToolSnapshot() && !safeScope.wasToolExposed(tool.name())) {
            return "Tool was not exposed to the model in this request. Use tool_search first; "
                    + "loaded tools become callable on the next model request.";
        }
        if (decision.exposed()) {
            return null;
        }
        return "Tool is not loaded in the current session. Use tool_search first; "
                + "loaded tools become callable on the next model request.";
    }

    // ---- Internals -------------------------------------------------------

    private ToolCapabilityProfile effectiveProfile(ToolAccessScope scope) {
        if (scope.explicitProfile() != null) {
            return scope.explicitProfile();
        }
        ToolCapabilityProfile derived = sessionProfile(scope.session());
        return derived != null ? derived : ToolCapabilityProfile.unrestricted();
    }

    private ToolCapabilityProfile sessionProfile(ConversationSession session) {
        if (session == null) {
            return null;
        }
        return session.capabilityProfile().toolCapability();
    }

    private boolean directlyExposed(ToolCapabilityProfile profile, String toolName) {
        return profile.exposesDirectly(toolName);
    }

    private static ToolAccessScope scope(ToolAccessScope scope) {
        return scope == null ? ToolAccessScope.forSession(null) : scope;
    }
}
