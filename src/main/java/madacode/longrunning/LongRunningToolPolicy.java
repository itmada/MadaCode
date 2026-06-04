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
 *   <li>{@code longrun_task_update} is reserved for worker sessions and
 *       is never visible/executable in the control session.</li>
 *   <li>{@code worker_report} is only visible in worker sessions.</li>
 *   <li>Pre-execution stages do not expose ordinary planning write tools such
 *       as {@code plan_create}, {@code plan_update}, or {@code todo_write}.</li>
 *   <li>Both tools are invisible and non-executable in COMMON mode or when
 *       the session is not in a long-running workflow.</li>
 * </ul>
 */
public final class LongRunningToolPolicy {

    private static final String TASK_UPDATE_TOOL = "longrun_task_update";
    private static final String WORKER_REPORT_TOOL = "worker_report";
    private static final String PLAN_UPDATE_TOOL = "longrun_plan_update";
    private static final String TRANSITION_REQUEST_TOOL = "longrun_state_transition_request";

    private static final Set<String> ORDINARY_PLAN_MODE_TOOLS = Set.of(
            "enter_plan_mode", "exit_plan_mode",
            "plan_create", "plan_get", "plan_list", "plan_update",
            "todo_write");

    private static final Set<String> PRE_EXECUTION_ALLOWED_WRITE_TOOLS = Set.of(
            "ask_user_question");

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
        // Worker session: task_update and worker_report visible in RUNNING.
        if (session.isLongRunningWorkerSession() && stage == LongRunningStage.RUNNING) {
            return switch (toolName) {
                case TASK_UPDATE_TOOL, WORKER_REPORT_TOOL -> true;
                default -> false;
            };
        }
        // Control session
        return switch (toolName) {
            case TASK_UPDATE_TOOL, WORKER_REPORT_TOOL -> false;
            case PLAN_UPDATE_TOOL -> stage == LongRunningStage.DRAFT;
            case TRANSITION_REQUEST_TOOL -> stage == LongRunningStage.DRAFT || stage == LongRunningStage.RUNNING;
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

        // Worker session in RUNNING: full tool access plus task-store tools.
        if (session.isLongRunningWorkerSession() && stage == LongRunningStage.RUNNING) {
            if (WORKER_REPORT_TOOL.equals(name)) return true;
            if (TASK_UPDATE_TOOL.equals(name)) return true;
            if (PLAN_UPDATE_TOOL.equals(name) || TRANSITION_REQUEST_TOOL.equals(name)) return false;
            return true; // All other tools available to worker
        }

        if (stage == LongRunningStage.DRAFT) {
            if (TASK_UPDATE_TOOL.equals(name)) return false;
            if (WORKER_REPORT_TOOL.equals(name)) return false;
            if (PLAN_UPDATE_TOOL.equals(name)) return true;
            if (TRANSITION_REQUEST_TOOL.equals(name)) return true;
            return tool.isReadOnly() || PRE_EXECUTION_ALLOWED_WRITE_TOOLS.contains(name);
        }

        if (stage == LongRunningStage.RUNNING) {
            // Control session: read-only only. No task updates, no worker report.
            if (TASK_UPDATE_TOOL.equals(name)) return false;
            if (WORKER_REPORT_TOOL.equals(name)) return false;
            if (PLAN_UPDATE_TOOL.equals(name)) return false;
            if (TRANSITION_REQUEST_TOOL.equals(name)) return true;
            return tool.isReadOnly();
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
            case TASK_UPDATE_TOOL ->
                "longrun_task_update is reserved for the worker session and is not available in the control session. Current stage: " + stage;
            case WORKER_REPORT_TOOL ->
                "worker_report is only available in a worker session. Current stage: " + stage;
            case PLAN_UPDATE_TOOL ->
                "longrun_plan_update is only available in the control session while the task is DRAFT. Current stage: " + stage;
            case TRANSITION_REQUEST_TOOL ->
                "longrun_state_transition_request is only available in the control session while the task is DRAFT or RUNNING. Current stage: " + stage;
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
                && session.longRunningStage() == LongRunningStage.DRAFT) {
            return "Current long-running stage is " + session.longRunningStage()
                    + ". This stage only allows draft planning, transition requests, read-only investigation, and ask_user_question. Do not attempt implementation until RUNNING.";
        }

        if (session != null
                && session.workflowMode() == SessionMode.LONG_RUNNING
                && session.longRunningStage() == LongRunningStage.RUNNING) {
            return "Current long-running stage is " + session.longRunningStage()
                    + ". The control session only allows read-only tools. Implementation is managed by the worker/launcher system.";
        }

        return executionDenialReason(tool.name(), session);
    }

    private static boolean isLongRunningTool(String toolName) {
        return TASK_UPDATE_TOOL.equals(toolName)
                || WORKER_REPORT_TOOL.equals(toolName)
                || PLAN_UPDATE_TOOL.equals(toolName)
                || TRANSITION_REQUEST_TOOL.equals(toolName);
    }
}
