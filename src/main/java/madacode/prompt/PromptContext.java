package madacode.prompt;

import madacode.core.session.ConversationSession;
import madacode.memory.MemoryLoader;
import madacode.skill.SkillRegistry;
import madacode.tool.VisibleTools;

import java.nio.file.Path;

public record PromptContext(
        VisibleTools visibleTools,
        Path workingDirectory,
        ConversationSession session,
        String agentContext,
        MemoryLoader memoryLoader,
        SkillRegistry skillRegistry) {
}
