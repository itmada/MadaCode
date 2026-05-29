package madacode.bootstrap;

import madacode.tool.AgentTool;
import madacode.tool.SkillTool;

final class AgentSkillToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        context.register(new AgentTool(context.agentRunner(), context.agentRegistry()));
        context.register(new SkillTool(context.skillRegistry(), context.agentRunner()));
    }
}
