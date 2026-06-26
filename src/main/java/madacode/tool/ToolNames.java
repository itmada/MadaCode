package madacode.tool;

/**
 * Canonical names for built-in tools that participate in cross-cutting runtime
 * policy.
 */
public final class ToolNames {

    public static final String AGENT = "agent";
    public static final String ASK_USER_QUESTION = "ask_user_question";
    public static final String BASH = "bash";
    public static final String FILE_EDIT = "file_edit";
    public static final String FILE_READ = "file_read";
    public static final String FILE_WRITE = "file_write";
    public static final String GLOB = "glob";
    public static final String GREP = "grep";
    public static final String UPDATE_PLAN = "update_plan";
    public static final String TOOL_SEARCH = "tool_search";

    // Long-running workflow lifecycle tools. Their capability/stage rules live in
    // madacode.longrunning.LongRunningCapabilityPolicy.
    public static final String WORKER_REPORT = "worker_report";
    public static final String LONGRUN_TASK_UPDATE = "longrun_task_update";
    public static final String LONGRUN_PLAN_UPDATE = "longrun_plan_update";
    public static final String LONGRUN_TASK_SUMMARY_UPDATE = "longrun_task_summary_update";
    public static final String LONGRUN_FEATURE_LIST_REPLACE = "longrun_feature_list_replace";
    public static final String LONGRUN_KNOWN_ISSUES_REPLACE = "longrun_known_issues_replace";
    public static final String LONGRUN_PROGRESS_APPEND = "longrun_progress_append";
    public static final String LONGRUN_STATE_TRANSITION_REQUEST = "longrun_state_transition_request";

    private ToolNames() {}
}
