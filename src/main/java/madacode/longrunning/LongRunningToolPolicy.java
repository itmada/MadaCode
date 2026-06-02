package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.Tool;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Unified policy governing which long-running tools are visible and executable
 * for a given session state.
 *
 * <p>This class is the <em>single authority</em> for long-running tool
 * visibility and execution gating. Both {@code SystemPromptBuilder} (prompt
 * generation) and {@code ToolExecutor} (execution) delegate to this class so
 * that prompt-hiding and execution-blocking can never diverge.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>{@code longrun_stage_update} is visible/executable only when the
 *       session is in LONG_RUNNING mode and the stage is
 *       {@code PLANNING} or {@code WAITING_FOR_APPROVAL}.</li>
 *   <li>{@code longrun_task_update} is visible/executable only when the
 *       session is in LONG_RUNNING mode and the stage is
 *       {@code EXECUTING}.</li>
 *   <li>Pre-execution stages do not expose ordinary planning write tools such
 *       as {@code plan_create}, {@code plan_update}, or {@code todo_write}.
 *       Long-running planning is represented by the task store and
 *       {@code longrun_stage_update}, not the normal checklist tools.</li>
 *   <li>Both tools are invisible and non-executable in COMMON mode or when
 *       the session is not in a long-running workflow.</li>
 * </ul>
 */
public final class LongRunningToolPolicy {

    private static final String STAGE_UPDATE_TOOL = "longrun_stage_update";
    private static final String TASK_UPDATE_TOOL = "longrun_task_update";

    private static final Set<String> ORDINARY_PLAN_MODE_TOOLS = Set.of(
            "enter_plan_mode", "exit_plan_mode",
            "plan_create", "plan_get", "plan_list", "plan_update",
            "todo_write");

    private static final Set<String> PRE_EXECUTION_ALLOWED_WRITE_TOOLS = Set.of(
            "ask_user_question", "longrun_stage_update");

    private LongRunningToolPolicy() {}

    /**
     * Returns {@code true} if the given tool is visible (and thus executable)
     * in the current session state.
     */
    public static boolean isToolVisible(String toolName, ConversationSession session) {
        if (!isLongRunningTool(toolName)) {
            return true;
        }
        if (session == null || session.workflowMode() != SessionMode.LONG_RUNNING) {
            return false;
        }
        LongRunningStage stage = session.longRunningStage();
        if (stage == null) {
            return false;
        }
        return switch (toolName) {
            case STAGE_UPDATE_TOOL -> stage == LongRunningStage.PLANNING
                    || stage == LongRunningStage.WAITING_FOR_APPROVAL;
            case TASK_UPDATE_TOOL -> stage == LongRunningStage.EXECUTING;
            default -> false;
        };
    }

    /**
     * Returns {@code true} if the given tool is visible in the current session
     * state, considering both long-running tool filtering and pre-execution
     * write-tool restrictions.
     */
    public static boolean isToolVisible(Tool<?> tool, ConversationSession session) {
        if (tool == null) return false;
        String name = tool.name();

        if (session == null || session.workflowMode() != SessionMode.LONG_RUNNING) {
            return !isLongRunningTool(name);
        }

        LongRunningStage stage = session.longRunningStage();
        if (stage == null) return !isLongRunningTool(name);
        if (ORDINARY_PLAN_MODE_TOOLS.contains(name)) return false;

        if (stage == LongRunningStage.PLANNING || stage == LongRunningStage.WAITING_FOR_APPROVAL) {
            if (TASK_UPDATE_TOOL.equals(name)) return false;
            if (STAGE_UPDATE_TOOL.equals(name)) return true;
            return tool.isReadOnly() || PRE_EXECUTION_ALLOWED_WRITE_TOOLS.contains(name);
        }

        if (stage == LongRunningStage.EXECUTING) {
            if (STAGE_UPDATE_TOOL.equals(name)) return false;
            return true;
        }

        return !isLongRunningTool(name);
    }

    /**
     * Filters a tool collection, retaining only tools that are visible for the
     * given session state.
     */
    public static Collection<Tool<?>> filterVisibleTools(
            Collection<Tool<?>> tools, ConversationSession session) {
        return tools.stream()
                .filter(tool -> isToolVisible(tool, session))
                .toList();
    }

    /**
     * Returns a denial reason if the tool is not allowed to execute in the
     * current session state, or {@code null} if it is allowed.
     *
     * <p>This is the <em>single hard execution guard</em> — even if a tool
     * somehow appears in a model request, it is rejected here. Callers that
     * only need a boolean check can test {@code reason != null}.
     *
     * <p>The allow/deny rules are identical to {@link #isToolVisible}; this
     * method adds human-readable reasons for diagnostics.
     */
    public static String executionDenialReason(String toolName, ConversationSession session) {
        if (!isLongRunningTool(toolName)) {
            return null;
        }
        if (isToolVisible(toolName, session)) {
            return null;
        }
        if (session == null || session.workflowMode() != SessionMode.LONG_RUNNING) {
            return "Long-running mode is not active for this session.";
        }
        LongRunningStage stage = session.longRunningStage();
        if (stage == null) {
            return "No long-running stage is active for this session.";
        }
        return switch (toolName) {
            case STAGE_UPDATE_TOOL ->
                "longrun_stage_update is only available in PLANNING and WAITING_FOR_APPROVAL stages. Current stage: " + stage;
            case TASK_UPDATE_TOOL ->
                "longrun_task_update is only available in the EXECUTING stage. Current stage: " + stage;
            default -> "Unknown long-running tool: " + toolName;
        };
    }

    /**
     * Returns a denial reason if the tool is not allowed to execute, using
     * the {@link Tool} object to check read-only status.
     *
     * @see #executionDenialReason(String, ConversationSession)
     */
    public static String executionDenialReason(Tool<?> tool, ConversationSession session) {
        if (tool == null) return "Unknown tool.";
        if (isToolVisible(tool, session)) return null;

        if (session != null
                && session.workflowMode() == SessionMode.LONG_RUNNING
                && (session.longRunningStage() == LongRunningStage.PLANNING
                    || session.longRunningStage() == LongRunningStage.WAITING_FOR_APPROVAL)) {
            return "Long-running pre-execution stage uses its own planning flow and only allows read-only investigation tools, ask_user_question, and longrun_stage_update. Current stage: "
                    + session.longRunningStage();
        }

        return executionDenialReason(tool.name(), session);
    }

    private static boolean isLongRunningTool(String toolName) {
        return STAGE_UPDATE_TOOL.equals(toolName) || TASK_UPDATE_TOOL.equals(toolName);
    }
}
