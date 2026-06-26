package madacode.bootstrap;

import madacode.tool.AddProviderTool;
import madacode.tool.AskUserQuestionTool;
import madacode.tool.LongRunFeatureListReplaceTool;
import madacode.tool.LongRunKnownIssuesReplaceTool;
import madacode.tool.LongRunPlanUpdateTool;
import madacode.tool.LongRunProgressAppendTool;
import madacode.tool.LongRunStateTransitionRequestTool;
import madacode.tool.LongRunTaskUpdateTool;
import madacode.tool.LongRunTaskSummaryUpdateTool;
import madacode.tool.ToolSearchTool;
import madacode.tool.WorkerReportTool;

final class InteractionToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        var env = context.environment();
        context.register(new AskUserQuestionTool());
        context.register(new LongRunPlanUpdateTool());
        context.register(new LongRunTaskSummaryUpdateTool());
        context.register(new LongRunFeatureListReplaceTool());
        context.register(new LongRunKnownIssuesReplaceTool());
        context.register(new LongRunProgressAppendTool());
        context.register(new LongRunStateTransitionRequestTool());
        context.register(new LongRunTaskUpdateTool());
        context.register(new WorkerReportTool());
        context.register(new AddProviderTool(env.providerRegistry(), env.providerLoader()));
        context.register(new ToolSearchTool(context.registry(), context.toolAccessResolver()));
    }
}
