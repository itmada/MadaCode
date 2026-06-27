package madacode.bootstrap;

import madacode.tool.AddProviderTool;
import madacode.tool.AskUserQuestionTool;
import madacode.tool.LongRunEnvironmentReadTool;
import madacode.tool.LongRunEnvironmentUpdateTool;
import madacode.tool.LongRunStateTransitionTool;
import madacode.tool.ToolSearchTool;
import madacode.tool.WorkerReportTool;

final class InteractionToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        var env = context.environment();
        context.register(new AskUserQuestionTool());
        context.register(new LongRunEnvironmentReadTool());
        context.register(new LongRunEnvironmentUpdateTool());
        context.register(new LongRunStateTransitionTool());
        context.register(new WorkerReportTool());
        context.register(new AddProviderTool(env.providerRegistry(), env.providerLoader()));
        context.register(new ToolSearchTool(context.registry(), context.toolAccessResolver()));
    }
}
