package madacode.agent;

import java.util.List;

/**
 * Loader that returns the hardcoded built-in agents defined in
 * {@link BuiltInAgents}. Serves as the lowest-priority source for
 * {@link AgentRegistry} since user/project loaders may override these by name.
 */
public final class BuiltInAgentLoader implements AgentLoader {

    @Override
    public List<AgentDefinition> load() {
        return BuiltInAgents.getAll();
    }
}
