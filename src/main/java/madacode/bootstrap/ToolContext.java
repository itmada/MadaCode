package madacode.bootstrap;

import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.mcp.McpConnectionManager;
import madacode.memory.MemoryLoader;
import madacode.skill.SkillRegistry;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.access.ToolAccessResolver;

final class ToolContext {

    private final EnvironmentRuntime environment;
    private final BootstrapResources resources;
    private final ToolRegistry registry;
    private final ToolAccessResolver toolAccessResolver;
    private final SkillRegistry skillRegistry;
    private final AgentRegistry agentRegistry;
    private final AgentRunner agentRunner;
    private MemoryLoader memory = MemoryLoader.disabled();
    private McpConnectionManager mcpManager;

    ToolContext(
            EnvironmentRuntime environment,
            BootstrapResources resources,
            ToolRegistry registry,
            ToolAccessResolver toolAccessResolver,
            SkillRegistry skillRegistry,
            AgentRegistry agentRegistry,
            AgentRunner agentRunner) {
        this.environment = environment;
        this.resources = resources;
        this.registry = registry;
        this.toolAccessResolver = toolAccessResolver;
        this.skillRegistry = skillRegistry;
        this.agentRegistry = agentRegistry;
        this.agentRunner = agentRunner;
    }

    EnvironmentRuntime environment() {
        return environment;
    }

    BootstrapResources resources() {
        return resources;
    }

    ToolRegistry registry() {
        return registry;
    }

    ToolAccessResolver toolAccessResolver() {
        return toolAccessResolver;
    }

    SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    AgentRegistry agentRegistry() {
        return agentRegistry;
    }

    AgentRunner agentRunner() {
        return agentRunner;
    }

    MemoryLoader memory() {
        return memory;
    }

    void memory(MemoryLoader memory) {
        this.memory = memory;
    }

    McpConnectionManager mcpManager() {
        return mcpManager;
    }

    void mcpManager(McpConnectionManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    void register(Tool<?> tool) {
        registry.register(tool);
    }
}
