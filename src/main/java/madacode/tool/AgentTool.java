package madacode.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.agent.AgentDefinition;
import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.core.model.MetaEvent;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.core.turn.TurnResult;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class AgentTool implements Tool<AgentTool.Input> {

    public record Input(
            String description,
            String prompt,
            @JsonProperty("subagent_type") String subagentType) {}

    private static final String DEFAULT_AGENT_TYPE = "explorer";
    private static final String NO_AGENTS = "no agents loaded";

    private final AgentRunner agentRunner;
    private final AgentRegistry agentRegistry;

    public AgentTool(AgentRunner agentRunner, AgentRegistry agentRegistry) {
        this.agentRunner = Objects.requireNonNull(agentRunner, "agentRunner");
        this.agentRegistry = Objects.requireNonNull(agentRegistry, "agentRegistry");
    }

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public String description() {
        return "Delegates work to a sub-agent. Available: " + availableTypes() + ". "
                + "Use sub-agents for broad codebase exploration, independent investigations that can run in parallel, "
                + "or specialized read-only review and planning passes. Do not use sub-agents to read one known file, "
                + "perform a small targeted edit, or duplicate work already delegated. When writing the prompt, assume "
                + "the sub-agent has no useful context unless you provide it. Include the goal, relevant files, what you "
                + "already know, what to avoid, and the expected output shape.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isPlanModeSafe() {
        return true;
    }

    @Override
    public boolean bypassesHooks() {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("description", ToolSchemas.stringProperty(
                mapper, "Short (3-5 word) description of the task."));
        properties.set("prompt", ToolSchemas.stringProperty(
                mapper, "The task for the sub-agent to perform."));
        properties.set("subagent_type", ToolSchemas.stringProperty(
                mapper, "Sub-agent type (" + availableTypes()
                        + "). Defaults to " + DEFAULT_AGENT_TYPE + "."));
        return ToolSchemas.objectSchema(mapper, properties, "description", "prompt");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        if (!context.canSpawnSubAgent()) {
            return new ToolResult(name(), false, "Maximum agent depth reached");
        }

        String prompt = input.prompt() == null ? "" : input.prompt();
        if (prompt.isBlank()) {
            return new ToolResult(name(), false, "Missing required field: prompt");
        }

        String agentType = input.subagentType() == null || input.subagentType().isBlank()
                ? DEFAULT_AGENT_TYPE
                : input.subagentType();
        Optional<AgentDefinition> found = agentRegistry.findByType(agentType);
        if (found.isEmpty()) {
            return new ToolResult(name(), false,
                    "Unknown subagent_type: \"" + agentType + "\". Available: " + availableTypes());
        }

        String label = input.description() == null || input.description().isBlank()
                ? prompt
                : input.description();
        context.session().fireMetaEvent(new MetaEvent.SubAgentStarted(label, agentType));

        TurnResult result = agentRunner.run(found.get(), prompt, context);
        return AgentResults.toToolResult(name(), result);
    }

    private String availableTypes() {
        if (agentRegistry.all().isEmpty()) {
            return NO_AGENTS;
        }
        return agentRegistry.all().stream()
                .map(AgentDefinition::agentType)
                .collect(Collectors.joining(", "));
    }
}
