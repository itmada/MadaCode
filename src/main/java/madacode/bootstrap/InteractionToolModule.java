package madacode.bootstrap;

import madacode.tool.AddProviderTool;
import madacode.tool.AskUserQuestionTool;
import madacode.tool.EnterPlanModeTool;
import madacode.tool.ExitPlanModeTool;
import madacode.tool.LongRunPlanUpdateTool;
import madacode.tool.LongRunStateTransitionRequestTool;
import madacode.tool.LongRunTaskUpdateTool;
import madacode.tool.WorkerReportTool;

final class InteractionToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        var env = context.environment();
        context.register(new AskUserQuestionTool());
        context.register(new EnterPlanModeTool());
        context.register(new ExitPlanModeTool());
        context.register(new LongRunPlanUpdateTool());
        context.register(new LongRunStateTransitionRequestTool());
        context.register(new LongRunTaskUpdateTool());
        context.register(new WorkerReportTool());
        context.register(new AddProviderTool(env.providerRegistry(), env.providerLoader()));
    }
}
