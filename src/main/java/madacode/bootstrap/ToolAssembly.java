package madacode.bootstrap;

import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.agent.BuiltInAgentLoader;
import madacode.agent.BuiltInAgents;
import madacode.agent.DiskAgentLoader;
import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.permission.PermissionGate;
import madacode.skill.BundledSkillLoader;
import madacode.skill.DiskSkillLoader;
import madacode.skill.SkillRegistry;
import madacode.skill.SkillSource;
import madacode.skill.SkillStateStore;
import madacode.tool.ToolRegistry;

import java.util.List;

final class ToolAssembly {

    private ToolAssembly() {
    }

    static ToolRuntime create(EnvironmentRuntime environment, BootstrapResources resources, PermissionGate permission) {
        ToolRegistry registry = new ToolRegistry();
        SkillRegistry skills = initSkills(environment);
        AgentRegistry agents = initAgents(environment);
        AgentRunner agentRunner = new AgentRunner(registry, environment.api(), permission);
        ToolContext context = new ToolContext(
                environment, resources, registry, skills, agents, agentRunner);

        List<ToolModule> modules = List.of(
                new FileToolModule(),
                new WebToolModule(),
                new AgentSkillToolModule(),
                new InteractionToolModule(),
                new PlanToolModule(),
                new MemoryToolModule(),
                new McpToolModule());
        for (ToolModule module : modules) {
            module.install(context);
        }

        return new ToolRuntime(
                registry,
                context.memory(),
                context.mcpManager(),
                skills,
                agents);
    }

    private static SkillRegistry initSkills(EnvironmentRuntime environment) {
        SkillStateStore stateStore = new SkillStateStore(
                environment.homeDir().resolve(".mada/skills.json"));
        stateStore.load();

        SkillRegistry registry = new SkillRegistry(stateStore,
                new BundledSkillLoader(),
                new DiskSkillLoader(
                        environment.homeDir().resolve(".mada/skills"),
                        SkillSource.USER),
                new DiskSkillLoader(
                        environment.projectDir().resolve(".mada/skills"),
                        SkillSource.PROJECT));
        registry.reload();

        int total = registry.all().size();
        int enabled = registry.enabled().size();
        if (total > 0) {
            AppEvents.publisher().publish(UserVisibleEvent.info(
                    EventContext.bootstrap("SkillRegistry"),
                    "Skills loaded: " + enabled + "/" + total + " enabled"));
        }
        return registry;
    }

    private static AgentRegistry initAgents(EnvironmentRuntime environment) {
        AgentRegistry registry = AgentRegistry.loaded(
                new BuiltInAgentLoader(),
                new DiskAgentLoader(environment.homeDir().resolve(".mada/agents")),
                new DiskAgentLoader(environment.projectDir().resolve(".mada/agents")));

        int total = registry.all().size();
        int builtIn = BuiltInAgents.getAll().size();
        if (total > builtIn) {
            AppEvents.publisher().publish(UserVisibleEvent.info(
                    EventContext.bootstrap("AgentRegistry"),
                    "Agents loaded: " + total
                            + " (" + builtIn + " built-in + " + (total - builtIn) + " custom)"));
        }
        return registry;
    }
}
