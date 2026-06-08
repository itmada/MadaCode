package madacode.bootstrap;

import madacode.memory.AgentsMdLoader;
import madacode.memory.MemoryLoader;
import madacode.memory.MemoryStore;
import madacode.tool.MemorySaveTool;

final class MemoryToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        if (!context.environment().memoryEnabled()) {
            context.memory(MemoryLoader.disabled());
            return;
        }
        MemoryStore memoryStore = new MemoryStore(
                context.environment().paths().globalMemoryDir());
        context.register(new MemorySaveTool(memoryStore));
        context.memory(new MemoryLoader(new AgentsMdLoader(), memoryStore, true));
    }
}
