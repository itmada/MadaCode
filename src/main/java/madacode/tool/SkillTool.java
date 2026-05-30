package madacode.tool;

import madacode.agent.AgentDefinition;
import madacode.agent.AgentRunner;
import madacode.core.model.MetaEvent;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.core.turn.TurnResult;
import madacode.permission.PermissionMode;
import madacode.skill.Skill;
import madacode.skill.SkillBodyRenderer;
import madacode.skill.SkillRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class SkillTool implements Tool<SkillTool.Input> {

    public record Input(String skill, String task) {}

    private final SkillRegistry registry;
    private final AgentRunner agentRunner;

    public SkillTool(SkillRegistry registry, AgentRunner agentRunner) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.agentRunner = Objects.requireNonNull(agentRunner, "agentRunner");
    }

    @Override
    public String name() { return "skill"; }

    @Override
    public String description() {
        String names = registry.enabled().stream()
                .map(Skill::name).collect(Collectors.joining(", "));
        return "Load a skill for specialized workflows. "
                + "Available: " + (names.isEmpty() ? "none" : names) + ". "
                + "Use when the task matches a skill's purpose.";
    }

    @Override
    public Class<Input> inputType() { return Input.class; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("skill", ToolSchemas.stringProperty(mapper,
                "The skill to load"));
        properties.set("task", ToolSchemas.stringProperty(mapper,
                "What to do (e.g. 'review current changes')"));
        return ToolSchemas.objectSchema(mapper, properties, "skill", "task");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String skillName = input.skill() == null ? "" : input.skill().strip();
        if (skillName.isBlank()) {
            return new ToolResult(name(), false, "skill is required");
        }

        Skill s = registry.find(skillName).orElse(null);
        if (s == null) {
            String available = registry.enabled().stream()
                    .map(Skill::name).collect(Collectors.joining(", "));
            return new ToolResult(name(), false,
                    "Unknown skill: \"" + skillName
                            + "\". Available: " + available);
        }

        String rendered = SkillBodyRenderer.render(s,
                input.task() == null ? "" : input.task().strip());

        if ("fork".equals(s.mode())) {
            if (!context.canSpawnSubAgent()) {
                return new ToolResult(name(), false, "Maximum agent depth reached");
            }
            context.session().fireMetaEvent(new MetaEvent.SubAgentStarted(s.name(), "skill"));
            AgentDefinition def = new AgentDefinition(
                    s.name(), s.description(), s.whenToUse(),
                    rendered,
                    Set.copyOf(s.allowedTools()),
                    Set.copyOf(s.disallowedTools()),
                    s.maxIterations(), s.maxToolCalls(), PermissionMode.ACCEPT_EDITS);
            TurnResult result = agentRunner.run(def, rendered, context);
            return AgentResults.toToolResult(name(), result);
        }

        // inline: return the rendered prompt body directly
        return new ToolResult(name(), true, rendered);
    }
}
