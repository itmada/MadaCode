package madacode.prompt;

import madacode.core.ConversationSession;
import madacode.memory.MemoryLoader;
import madacode.skill.Skill;
import madacode.skill.SkillRegistry;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Collectors;

public class SystemPromptBuilder {

    // Only shown if plan tools are in the registry — keeps simple turns lean.
    private static final String TASK_GUIDANCE =
            "For complex work, use plan_create to break work into manageable tasks. "
                    + "Mark each task completed as soon as it is done — don't batch.";

    private final String agentContext;
    private final MemoryLoader memoryLoader;
    private final SkillRegistry skillRegistry;

    public SystemPromptBuilder() {
        this(null, null, null);
    }

    public SystemPromptBuilder(String agentContext) {
        this(agentContext, null, null);
    }

    public SystemPromptBuilder(MemoryLoader memoryLoader) {
        this(null, memoryLoader, null);
    }

    public SystemPromptBuilder(MemoryLoader memoryLoader, SkillRegistry skillRegistry) {
        this(null, memoryLoader, skillRegistry);
    }

    public SystemPromptBuilder(String agentContext, MemoryLoader memoryLoader) {
        this(agentContext, memoryLoader, null);
    }

    public SystemPromptBuilder(String agentContext, MemoryLoader memoryLoader,
                               SkillRegistry skillRegistry) {
        this.agentContext = agentContext;
        this.memoryLoader = memoryLoader;
        this.skillRegistry = skillRegistry;
    }

    public String build(Collection<Tool<?>> tools) {
        return build(tools, null, null);
    }

    public String build(Collection<Tool<?>> tools, Path cwd) {
        return build(tools, cwd, null);
    }

    public String build(Collection<Tool<?>> tools, Path cwd, ConversationSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are MadaCode agent. Available tools: ");
        sb.append(tools.stream().map(Tool::name).collect(Collectors.joining(", ")));

        sb.append("\n\n").append(TASK_GUIDANCE);

        // Skill listing — name + description so model knows what's available
        boolean hasSkillTool = tools.stream().anyMatch(t -> "skill".equals(t.name()));
        if (hasSkillTool && skillRegistry != null) {
            var enabledSkills = skillRegistry.enabled();
            if (!enabledSkills.isEmpty()) {
                sb.append("\n\n## Skills\n");
                sb.append("Invoke via skill(skill=\"<name>\", task=\"...\")\n");
                for (Skill s : enabledSkills) {
                    sb.append("- **").append(s.name()).append("**");
                    if (!s.description().isBlank()) {
                        sb.append(": ").append(s.description());
                    }
                    if (!s.whenToUse().isBlank()) {
                        sb.append(" (").append(s.whenToUse()).append(")");
                    }
                    sb.append("\n");
                }
            }
        }

        if (memoryLoader != null && cwd != null) {
            memoryLoader.renderForSystemPrompt(cwd).ifPresent(section -> {
                sb.append("\n\n## Project & user context\n");
                sb.append(section);
            });
        }

        if (session != null) {
            // Only inject active tasks — completed are historical noise.
            // The model uses plan_list/plan_get to query details when needed.
            var active = session.plan().items().stream()
                    .filter(t -> t.status() == PlanStatus.IN_PROGRESS
                            || t.status() == PlanStatus.PENDING)
                    .toList();
            if (!active.isEmpty()) {
                sb.append("\n\n## Active Tasks\n");
                for (PlanItem t : active) {
                    sb.append("- [").append(t.status()).append("] ");
                    if (t.status() == PlanStatus.IN_PROGRESS) {
                        sb.append("▶ ");
                    }
                    sb.append(t.id()).append("  ").append(t.title());
                    var blockers = session.plan().validateCanStart(t);
                    if (!blockers.isEmpty()) {
                        sb.append(" (blocked by: ")
                                .append(String.join(", ", blockers)).append(")");
                    }
                    sb.append("\n");
                }
            }
        }

        if (agentContext != null) {
            sb.append("\n\n").append(agentContext);
        }

        return sb.toString();
    }
}
