package madacode.tool;

import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.longrunning.LongRunningTaskStore;
import madacode.tool.schema.OptionalSchemaProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LongRunFeatureListReplaceTool
        extends LongRunPlanUpdateSupport<LongRunFeatureListReplaceTool.Input> {

    public record Input(
            @OptionalSchemaProperty
            String task_id,
            List<FeatureInput> features) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_FEATURE_LIST_REPLACE;
    }

    @Override
    public String description() {
        return "Controller: replace the long-running feature list. "
                + "Pass the full feature_list.json (each feature with id, description, dependencies, priority, pass status); "
                + "the previous version is overwritten.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    protected String actionName() {
        return "replace_feature_list";
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
        return replaceFeatureList(store, taskId, input.features());
    }

    @Override
    protected Map<String, String> eventDetails(Input input) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("featureCount", String.valueOf(input.features().size()));
        return Map.copyOf(details);
    }
}
