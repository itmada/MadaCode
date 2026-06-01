package madacode.bootstrap;

import madacode.tool.AddProviderTool;
import madacode.tool.AskUserQuestionTool;
import madacode.tool.EnterPlanModeTool;
import madacode.tool.ExitPlanModeTool;
import madacode.tool.LongRunStageUpdateTool;

final class InteractionToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        var env = context.environment();
        context.register(new AskUserQuestionTool());
        context.register(new EnterPlanModeTool());
        context.register(new ExitPlanModeTool());
        context.register(new LongRunStageUpdateTool());
        context.register(new AddProviderTool(env.providerRegistry(), env.providerLoader()));
    }
}
