package madacode.bootstrap;

import madacode.tool.UpdatePlanTool;

final class PlanToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        context.register(new UpdatePlanTool());
    }
}
