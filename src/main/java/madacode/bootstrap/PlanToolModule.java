package madacode.bootstrap;

import madacode.tool.PlanCreateTool;
import madacode.tool.PlanGetTool;
import madacode.tool.PlanListTool;
import madacode.tool.PlanUpdateTool;
import madacode.tool.TodoWriteTool;

final class PlanToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        context.register(new PlanCreateTool());
        context.register(new PlanGetTool());
        context.register(new PlanListTool());
        context.register(new PlanUpdateTool());
        context.register(new TodoWriteTool());
    }
}
