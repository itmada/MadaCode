package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.longrunning.LongRunningTaskStore;
import madacode.tool.schema.OptionalSchemaProperty;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LongRunTaskSummaryUpdateTool
        extends LongRunPlanUpdateSupport<LongRunTaskSummaryUpdateTool.Input> {

    public record Input(
            @OptionalSchemaProperty
            String task_id,
            @OptionalSchemaProperty
            String title,
            @OptionalSchemaProperty
            String reason,
            String plan_summary) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_TASK_SUMMARY_UPDATE;
    }

    @Override
    public String description() {
        return "Controller: rewrite the long-running task summary (goal, scope, constraints) in task.json. "
                + "Use while shaping or revising the plan.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    protected String actionName() {
        return "update_task_summary";
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
        return updatePlanSummary(store, taskId, input.title(), input.reason(), input.plan_summary(), session);
    }

    @Override
    protected Map<String, String> eventDetails(Input input) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "title", input.title());
        putIfPresent(details, "reason", input.reason());
        if (input.plan_summary() != null) {
            details.put("planSummaryLength", String.valueOf(input.plan_summary().strip().length()));
        }
        return Map.copyOf(details);
    }
}
