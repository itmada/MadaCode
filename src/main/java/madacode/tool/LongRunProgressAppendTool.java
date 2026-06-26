package madacode.tool;

import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.longrunning.LongRunningTaskStore;
import madacode.tool.schema.OptionalSchemaProperty;

public final class LongRunProgressAppendTool
        extends LongRunPlanUpdateSupport<LongRunProgressAppendTool.Input> {

    public record Input(
            @OptionalSchemaProperty
            String task_id,
            String text) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_PROGRESS_APPEND;
    }

    @Override
    public String description() {
        return "Append durable progress text to the long-running draft progress.txt.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    protected String actionName() {
        return "append_progress";
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
        return appendProgress(store, taskId, input.text());
    }
}
