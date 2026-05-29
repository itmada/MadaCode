package madacode.bootstrap;

import madacode.tool.AskUserQuestionTool;
import madacode.tool.EnterPlanModeTool;
import madacode.tool.ExitPlanModeTool;

final class InteractionToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        context.register(new AskUserQuestionTool());
        context.register(new EnterPlanModeTool());
        context.register(new ExitPlanModeTool());
    }
}
