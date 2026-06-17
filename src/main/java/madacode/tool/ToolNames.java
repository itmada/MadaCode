package madacode.tool;

/**
 * Canonical names for built-in tools that participate in cross-cutting runtime
 * policy.
 */
public final class ToolNames {

    public static final String AGENT = "agent";
    public static final String ASK_USER_QUESTION = "ask_user_question";
    public static final String BASH = "bash";
    public static final String ENTER_PLAN_MODE = "enter_plan_mode";
    public static final String EXIT_PLAN_MODE = "exit_plan_mode";
    public static final String FILE_EDIT = "file_edit";
    public static final String FILE_READ = "file_read";
    public static final String FILE_WRITE = "file_write";
    public static final String GLOB = "glob";
    public static final String GREP = "grep";
    public static final String PLAN_CREATE = "plan_create";
    public static final String PLAN_GET = "plan_get";
    public static final String PLAN_LIST = "plan_list";
    public static final String PLAN_UPDATE = "plan_update";
    public static final String TODO_WRITE = "todo_write";
    public static final String TOOL_SEARCH = "tool_search";

    // Long-running workflow lifecycle tools. Their capability/stage rules live in
    // madacode.longrunning.LongRunningCapabilityPolicy.
    public static final String WORKER_REPORT = "worker_report";
    public static final String LONGRUN_TASK_UPDATE = "longrun_task_update";
    public static final String LONGRUN_PLAN_UPDATE = "longrun_plan_update";
    public static final String LONGRUN_STATE_TRANSITION_REQUEST = "longrun_state_transition_request";

    private ToolNames() {}
}
