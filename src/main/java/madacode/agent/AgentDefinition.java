package madacode.agent;

import madacode.permission.PermissionMode;

import java.util.Objects;
import java.util.Set;

public final class AgentDefinition {

    private final String agentType;
    private final String description;
    private final String whenToUse;
    private final String systemPrompt;
    private final Set<String> allowedTools;
    private final Set<String> disallowedTools;
    private final boolean allowedToolsSpecified;
    private final Integer maxIterations;
    private final PermissionMode permissionMode;

    /** Convenience constructor for simple definitions with no turn limit. */
    public AgentDefinition(String name, String description, Set<String> allowedTools) {
        this(name, description, "", "", allowedTools, Set.of(),
                true, null, PermissionMode.EDIT);
    }

    public AgentDefinition(String agentType, String description, String whenToUse,
                           String systemPrompt, Set<String> allowedTools,
                           Set<String> disallowedTools, Integer maxIterations, PermissionMode permissionMode) {
        this(agentType, description, whenToUse, systemPrompt, allowedTools, disallowedTools,
                allowedTools != null, maxIterations, permissionMode);
    }

    public AgentDefinition(String agentType, String description, String whenToUse,
                           String systemPrompt, Set<String> allowedTools,
                           Set<String> disallowedTools, boolean allowedToolsSpecified,
                           Integer maxIterations, PermissionMode permissionMode) {
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.description = Objects.requireNonNull(description, "description");
        this.whenToUse = Objects.requireNonNull(whenToUse, "whenToUse");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.allowedTools = Set.copyOf(allowedTools == null ? Set.of() : allowedTools);
        this.disallowedTools = Set.copyOf(disallowedTools == null ? Set.of() : disallowedTools);
        this.allowedToolsSpecified = allowedToolsSpecified;
        this.maxIterations = requirePositiveOrNull(maxIterations, "maxIterations");
        this.permissionMode = permissionMode == null ? PermissionMode.EDIT : permissionMode;
    }

    private static Integer requirePositiveOrNull(Integer value, String fieldName) {
        if (value != null && value <= 0) {
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
    public boolean allowedToolsSpecified() { return allowedToolsSpecified; }
    public Integer maxIterations()  { return maxIterations; }
    public PermissionMode permissionMode() { return permissionMode; }
}
