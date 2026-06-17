package madacode.tool.access;

import madacode.core.session.ConversationSession;
import madacode.longrunning.LongRunningToolPolicy;
import madacode.tool.Tool;
import madacode.tool.ToolNames;
import madacode.tool.VisibleTools;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Single entry point for deciding whether a tool is visible, loadable, or
 * callable in the current agent/session/request context.
 *
 * <p>This class intentionally preserves today's behavior while centralising the
 * scattered checks. Future agent profiles and workflow overlays should plug in
 * here instead of growing new visibility filters.
 */
public final class ToolAccessResolver {

    private static final Set<String> ALWAYS_VISIBLE = Set.of(
            ToolNames.BASH,
            ToolNames.FILE_READ,
            ToolNames.FILE_WRITE,
            ToolNames.FILE_EDIT,
            ToolNames.GLOB,
            ToolNames.GREP,
            ToolNames.TODO_WRITE,
            ToolNames.PLAN_CREATE,
            ToolNames.PLAN_GET,
            ToolNames.PLAN_LIST,
            ToolNames.PLAN_UPDATE,
            ToolNames.ENTER_PLAN_MODE,
            ToolNames.EXIT_PLAN_MODE,
            ToolNames.ASK_USER_QUESTION,
            ToolNames.TOOL_SEARCH
    );

    private static final ToolAccessResolver DEFAULT = new ToolAccessResolver();

    public static ToolAccessResolver defaultResolver() {
        return DEFAULT;
    }

    public boolean isAlwaysVisible(String toolName) {
        return ALWAYS_VISIBLE.contains(toolName);
    }

    public VisibleTools visibleTools(Collection<Tool<?>> tools, ConversationSession session) {
        return visibleTools(tools, ToolAccessScope.unrestricted(session));
    }

    public VisibleTools visibleTools(Collection<Tool<?>> tools, ToolAccessScope scope) {
        Collection<Tool<?>> safeTools = tools == null ? List.of() : tools;
        return new VisibleTools(safeTools.stream()
                .filter(tool -> decideForVisibility(tool, scope).visibleNow())
                .toList());
    }

    public ToolAccessDecision decideForVisibility(Tool<?> tool, ConversationSession session) {
        return decideForVisibility(tool, ToolAccessScope.unrestricted(session));
    }

    public ToolAccessDecision decideForVisibility(
            Tool<?> tool,
            ConversationSession session,
            AgentToolProfile profile) {
        return decideForVisibility(tool, ToolAccessScope.forAgent(session, profile, Set.of()));
    }

    public ToolAccessDecision decideForVisibility(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return ToolAccessDecision.deny(null, "Unknown tool.");
        }
        ToolAccessScope safeScope = scope(scope);
        ConversationSession session = safeScope.session();
        AgentToolProfile safeProfile = safeScope.agentToolProfile();
        if (!safeProfile.allows(tool.name())) {
            return ToolAccessDecision.deny(tool.name(), "Tool is not part of the current agent capability set.");
        }
        String policyReason = LongRunningToolPolicy.executionDenialReason(tool, session);
        if (policyReason != null) {
            return ToolAccessDecision.deny(tool.name(), policyReason);
        }
        if (isAlwaysVisible(tool.name())) {
            return ToolAccessDecision.allowVisible(tool.name());
        }
        if (safeProfile.explicitlyAllows(tool.name())) {
            return ToolAccessDecision.allowVisible(tool.name());
        }
        if (safeScope.isDeferredToolLoaded(tool.name())) {
            return ToolAccessDecision.allowVisible(tool.name());
        }
        if (LongRunningToolPolicy.isSessionVisibleTool(tool, session)) {
            return ToolAccessDecision.allowVisible(tool.name());
        }
        return ToolAccessDecision.allowLoadable(tool.name());
    }

    public ToolAccessDecision decideForToolSearch(Tool<?> tool, ConversationSession session) {
        return decideForToolSearch(tool, ToolAccessScope.unrestricted(session));
    }

    public ToolAccessDecision decideForToolSearch(
            Tool<?> tool,
            ConversationSession session,
            AgentToolProfile profile) {
        return decideForToolSearch(tool, ToolAccessScope.forAgent(session, profile, Set.of()));
    }

    public ToolAccessDecision decideForToolSearch(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return ToolAccessDecision.deny(null, "Unknown tool.");
        }
        ToolAccessScope safeScope = scope(scope);
        AgentToolProfile safeProfile = safeScope.agentToolProfile();
        if (!safeProfile.allows(tool.name())) {
            return ToolAccessDecision.deny(tool.name(),
                    "Tool is not part of the current agent capability set.");
        }
        ToolAccessDecision visibility = decideForVisibility(tool, safeScope);
        if (visibility.visibleNow()) {
            return ToolAccessDecision.deny(tool.name(), tool.name() + ": already visible");
        }
        if (visibility.denied()) {
            return visibility;
        }
        return ToolAccessDecision.allowLoadable(tool.name());
    }

    public String executionDenialReason(Tool<?> tool, ConversationSession session) {
        return executionDenialReason(tool, session, AgentToolProfile.unrestricted());
    }

    public String executionDenialReason(
            Tool<?> tool,
            ConversationSession session,
            AgentToolProfile profile) {
        return executionDenialReason(tool, ToolAccessScope.forAgent(session, profile, Set.of()));
    }

    public String executionDenialReason(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return "Unknown tool.";
        }
        ToolAccessDecision decision = decideForVisibility(tool, scope);
        if (decision.callableNow()) {
            return null;
        }
        if (decision.denied()) {
            return decision.denialReason();
        }
        return "Tool is not loaded in the current session. Use tool_search first; "
                + "loaded tools become callable on the next model request.";
    }

    public String exposedToolDenialReason(Tool<?> tool, ToolAccessScope scope) {
        if (tool == null) {
            return "Unknown tool.";
        }
        ToolAccessScope safeScope = scope(scope);
        if (safeScope.hasExposedToolSnapshot()
                && !safeScope.wasToolExposed(tool.name())) {
            return "Tool was not exposed to the model in this request. Use tool_search first; "
                    + "loaded tools become callable on the next model request.";
        }
        return executionDenialReason(tool, safeScope);
    }

    private static ToolAccessScope scope(ToolAccessScope scope) {
        return scope == null ? ToolAccessScope.unrestricted(null) : scope;
    }
}
