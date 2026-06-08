package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningToolPolicy;

import java.util.Collection;
import java.util.Set;

public final class ToolVisibility {

    private static final Set<String> ALWAYS_VISIBLE = Set.of(
            "bash",
            "file_read",
            "file_write",
            "file_edit",
            "glob",
            "grep",
            "todo_write",
            "plan_create",
            "plan_get",
            "plan_list",
            "plan_update",
            "enter_plan_mode",
            "exit_plan_mode",
            "ask_user_question",
            ToolSearchTool.NAME
    );

    private ToolVisibility() {}

    public static boolean isAlwaysVisible(String toolName) {
        return ALWAYS_VISIBLE.contains(toolName);
    }

    public static Collection<Tool<?>> visibleToolsForSession(Collection<Tool<?>> tools,
                                                             ConversationSession session) {
        Collection<Tool<?>> safeTools = tools == null ? java.util.List.of() : tools;
        Set<String> loaded = session == null ? Set.of() : session.loadedDeferredTools();
        return safeTools.stream()
                .filter(tool -> LongRunningToolPolicy.isToolVisible(tool, session))
                .filter(tool -> isAlwaysVisible(tool.name())
                        || loaded.contains(tool.name())
                        || isActiveLongRunningTool(tool, session)
                        || isWorkerVisibleOrdinaryTool(tool, session))
                .toList();
    }

    public static String executionDenialReason(Tool<?> tool, ConversationSession session) {
        if (tool == null) {
            return "Unknown tool.";
        }
        String policyReason = LongRunningToolPolicy.executionDenialReason(tool, session);
        if (policyReason != null) {
            return policyReason;
        }
        if (isAlwaysVisible(tool.name())) {
            return null;
        }
        if (session != null && session.loadedDeferredTools().contains(tool.name())) {
            return null;
        }
        if (isActiveLongRunningTool(tool, session) || isWorkerVisibleOrdinaryTool(tool, session)) {
            return null;
        }
        return "Tool is not loaded in the current session. Use tool_search first; "
                + "loaded tools become callable on the next model request.";
    }

    public static String exposedToolDenialReason(Tool<?> tool, madacode.core.engine.ToolUseContext context) {
        if (tool == null) {
            return "Unknown tool.";
        }
        if (context != null && context.hasExposedToolSnapshot()
                && !context.wasToolExposed(tool.name())) {
            return "Tool was not exposed to the model in this request. Use tool_search first; "
                    + "loaded tools become callable on the next model request.";
        }
        return executionDenialReason(tool, context == null ? null : context.session());
    }

    private static boolean isActiveLongRunningTool(Tool<?> tool, ConversationSession session) {
        if (session == null || session.workflowMode() != SessionMode.LONG_RUNNING) {
            return false;
        }
        String name = tool.name();
        return "longrun_plan_update".equals(name)
                || "longrun_state_transition_request".equals(name)
                || "longrun_task_update".equals(name)
                || "worker_report".equals(name);
    }

    private static boolean isWorkerVisibleOrdinaryTool(Tool<?> tool, ConversationSession session) {
        if (session == null || !session.isLongRunningWorkerSession()) {
            return false;
        }
        return LongRunningToolPolicy.isToolVisible(tool, session);
    }
}
