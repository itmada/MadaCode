package madacode.tool;

/**
 * Canonical names for built-in tools that participate in cross-cutting runtime
 * policy.
 */
public final class ToolNames {

    public static final String AGENT = "agent";
    public static final String ADD_PROVIDER = "add_provider";
    public static final String ASK_USER_QUESTION = "ask_user_question";
    public static final String BASH = "bash";
    public static final String FILE_EDIT = "file_edit";
    public static final String FILE_READ = "file_read";
    public static final String FILE_WRITE = "file_write";
    public static final String GLOB = "glob";
    public static final String GREP = "grep";
    public static final String LIST_MCP_RESOURCES = "list_mcp_resources";
    public static final String MEMORY_SAVE = "memory_save";
    public static final String READ_MCP_RESOURCE = "read_mcp_resource";
    public static final String SKILL = "skill";
    public static final String UPDATE_PLAN = "update_plan";
    public static final String TOOL_SEARCH = "tool_search";
    public static final String WEB_FETCH = "web_fetch";

    // Long-running workflow lifecycle tools. Their capability/stage rules live in
    // madacode.longrunning.LongRunningCapabilityPolicy.
    public static final String WORKER_REPORT = "worker_report";
    public static final String LONGRUN_ENVIRONMENT_READ = "longrun_environment_read";
    public static final String LONGRUN_ENVIRONMENT_UPDATE = "longrun_environment_update";
    public static final String LONGRUN_STATE_TRANSITION = "longrun_state_transition";

    private ToolNames() {}
}
