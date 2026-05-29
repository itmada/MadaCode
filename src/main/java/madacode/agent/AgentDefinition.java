package madacode.agent;

import madacode.permission.PermissionMode;

import java.util.Objects;
import java.util.Set;

public final class AgentDefinition {

    private static final int DEFAULT_MAX_ITERATIONS = 15;
    private static final int DEFAULT_MAX_TOOL_CALLS = 50;

    private final String agentType;
    private final String description;
    private final String whenToUse;
    private final String systemPrompt;
    private final Set<String> allowedTools;
    private final Set<String> disallowedTools;
    private final int maxIterations;
    private final int maxToolCalls;
    private final PermissionMode permissionMode;

    /** Convenience constructor for simple definitions with default limits. */
    public AgentDefinition(String name, String description, Set<String> allowedTools) {
        this(name, description, "", "", allowedTools, Set.of(),
                DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_TOOL_CALLS, PermissionMode.ACCEPT_EDITS);
    }

    public AgentDefinition(String agentType, String description, String whenToUse,
                           String systemPrompt, Set<String> allowedTools,
                           Set<String> disallowedTools, int maxIterations, int maxToolCalls,
                           PermissionMode permissionMode) {
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.description = Objects.requireNonNull(description, "description");
        this.whenToUse = Objects.requireNonNull(whenToUse, "whenToUse");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.allowedTools = Set.copyOf(allowedTools);
        this.disallowedTools = Set.copyOf(disallowedTools);
        this.maxIterations = requirePositive(maxIterations, "maxIterations");
        this.maxToolCalls = requirePositive(maxToolCalls, "maxToolCalls");
        this.permissionMode = permissionMode == null ? PermissionMode.ACCEPT_EDITS : permissionMode;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive, was " + value);
        }
        return value;
    }

    public String agentType()       { return agentType; }
    public String name()            { return agentType; }
    public String description()     { return description; }
    public String whenToUse()       { return whenToUse; }
    public String systemPrompt()    { return systemPrompt; }
    public Set<String> allowedTools()    { return allowedTools; }
    public Set<String> disallowedTools() { return disallowedTools; }
    public int maxIterations()      { return maxIterations; }
    public int maxToolCalls()       { return maxToolCalls; }
    public PermissionMode permissionMode() { return permissionMode; }
}
