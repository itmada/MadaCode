package madacode.agent;

import java.util.List;

/**
 * Loads {@link AgentDefinition}s from a single source (built-in code,
 * disk directory, etc.). Composed by {@link AgentRegistry}.
 */
public interface AgentLoader {
    List<AgentDefinition> load();
}
