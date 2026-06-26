package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.tool.schema.OptionalSchemaProperty;

import java.util.List;
import java.util.Locale;

/**
 * Compatibility facade for older transcripts that call the former action-bag
 * tool. New controller prompts expose the single-action long-running draft
 * tools so their generated schemas can express action-specific required fields.
 */
public final class LongRunPlanUpdateTool implements Tool<LongRunPlanUpdateTool.Input> {

    public record Input(
            String action,
            @OptionalSchemaProperty
            String task_id,
            @OptionalSchemaProperty
            String title,
            @OptionalSchemaProperty
            String reason,
            @OptionalSchemaProperty
            String plan_summary,
            @OptionalSchemaProperty
            List<LongRunPlanUpdateSupport.FeatureInput> features,
            @OptionalSchemaProperty
            List<LongRunPlanUpdateSupport.IssueInput> issues,
            @OptionalSchemaProperty
            String text) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_PLAN_UPDATE;
    }

    @Override
    public String description() {
        return "Legacy compatibility facade for long-running draft task-store updates. "
                + "Prefer the single-action long-running draft tools.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isPlanModeSafe() {
        return false;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("action", ToolSchemas.stringEnumProperty(mapper,
                "Legacy durable task-store draft update action",
                "update_task_summary",
                "update_plan_summary",
                "replace_feature_list",
                "replace_known_issues",
                "append_progress"));
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "Optional task id. If present, it must match the active session task."));
        properties.set("title", ToolSchemas.stringProperty(
                mapper, "Optional replacement task title for update_task_summary."));
        properties.set("reason", ToolSchemas.stringProperty(
                mapper, "Optional draft reason such as requirements_updated for update_task_summary."));
        properties.set("plan_summary", ToolSchemas.stringProperty(
                mapper, "Updated durable structured task summary for update_task_summary."));
        properties.set("features", ToolSchemas.arrayProperty(
                mapper,
                "Full replacement feature list for replace_feature_list.",
                ToolSchemas.schemaFromRecord(mapper, LongRunPlanUpdateSupport.FeatureInput.class)));
        properties.set("issues", ToolSchemas.arrayProperty(
                mapper,
                "Full replacement known issue list for replace_known_issues.",
                ToolSchemas.schemaFromRecord(mapper, LongRunPlanUpdateSupport.IssueInput.class)));
        properties.set("text", ToolSchemas.stringProperty(
                mapper, "Progress text to append for append_progress."));
        return ToolSchemas.objectSchema(mapper, properties, "action");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String action = normalizeAction(input.action());
        try {
            return switch (action) {
                case "update_task_summary" -> new LongRunTaskSummaryUpdateTool().execute(
                        new LongRunTaskSummaryUpdateTool.Input(
                                input.task_id(), input.title(), input.reason(),
                                requirePresent(input.plan_summary(), "plan_summary")),
                        context);
                case "replace_feature_list" -> new LongRunFeatureListReplaceTool().execute(
                        new LongRunFeatureListReplaceTool.Input(
                                input.task_id(), requirePresent(input.features(), "features")),
                        context);
                case "replace_known_issues" -> new LongRunKnownIssuesReplaceTool().execute(
                        new LongRunKnownIssuesReplaceTool.Input(
                                input.task_id(), requirePresent(input.issues(), "issues")),
                        context);
                case "append_progress" -> new LongRunProgressAppendTool().execute(
                        new LongRunProgressAppendTool.Input(input.task_id(), requirePresent(input.text(), "text")),
                        context);
                default -> new ToolResult(name(), false,
                        "Unsupported long-running draft update action: " + safe(input.action()));
            };
        } catch (RuntimeException exception) {
            return new ToolResult(name(), false, exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private static String normalizeAction(String action) {
        String normalized = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        return "update_plan_summary".equals(normalized) ? "update_task_summary" : normalized;
    }

    private static <T> List<T> requirePresent(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must be present");
        }
        return values;
    }

    private static String requirePresent(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be present");
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "(missing)" : value;
    }
}
