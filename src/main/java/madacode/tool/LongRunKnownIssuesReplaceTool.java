package madacode.tool;

import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.longrunning.LongRunningTaskStore;
import madacode.tool.schema.OptionalSchemaProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LongRunKnownIssuesReplaceTool
        extends LongRunPlanUpdateSupport<LongRunKnownIssuesReplaceTool.Input> {

    public record Input(
            @OptionalSchemaProperty
            String task_id,
            List<IssueInput> issues) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_KNOWN_ISSUES_REPLACE;
    }

    @Override
    public String description() {
        return "Replace the durable long-running draft known_issues.json.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    protected String actionName() {
        return "replace_known_issues";
    }

    @Override
    protected String taskId(Input input) {
        return input.task_id();
    }

    @Override
    protected ToolResult apply(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        return replaceKnownIssues(store, taskId, input.issues());
    }

    @Override
    protected Map<String, String> eventDetails(Input input) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("issueCount", String.valueOf(input.issues().size()));
        return Map.copyOf(details);
    }
}
