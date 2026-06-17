package madacode.tool.access;

import madacode.tool.ToolNames;

import java.util.Objects;
import java.util.Set;

/**
 * Tool capability profile for the current agent runtime.
 *
 * <p>The main agent uses {@link #unrestricted()}. Sub-agents and forked skills
 * use explicit allow/deny sets from their definitions. Deferred loading still
 * controls when a scoped tool becomes visible, but this profile is the
 * authority on whether the current agent may ever use it.
 */
public record AgentToolProfile(
        String id,
        Set<String> allowedTools,
        Set<String> disallowedTools,
        boolean excludeRecursiveAgent) {

    private static final AgentToolProfile UNRESTRICTED =
            new AgentToolProfile("main", Set.of(), Set.of(), false);

    public AgentToolProfile {
        id = id == null || id.isBlank() ? "agent" : id.strip();
        allowedTools = Set.copyOf(Objects.requireNonNullElse(allowedTools, Set.of()));
        disallowedTools = Set.copyOf(Objects.requireNonNullElse(disallowedTools, Set.of()));
    }

    public static AgentToolProfile unrestricted() {
        return UNRESTRICTED;
    }

    public boolean allows(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (excludeRecursiveAgent && ToolNames.AGENT.equals(toolName)) {
            return false;
        }
        if (disallowedTools.contains(toolName)) {
            return false;
        }
        return allowedTools.isEmpty() || allowedTools.contains(toolName);
    }

    public boolean explicitlyAllows(String toolName) {
        return toolName != null && allowedTools.contains(toolName);
    }
}
