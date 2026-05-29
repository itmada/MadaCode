package madacode.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInAgentLoaderTest {

    @Test
    void returnsAllBuiltIns() {
        List<AgentDefinition> defs = new BuiltInAgentLoader().load();
        assertEquals(BuiltInAgents.getAll().size(), defs.size());
    }

    @Test
    void includesExplorerPlannerGeneral() {
        List<String> types = new BuiltInAgentLoader().load().stream()
                .map(AgentDefinition::agentType)
                .toList();
        assertTrue(types.contains("explorer"));
        assertTrue(types.contains("planner"));
        assertTrue(types.contains("general"));
    }
}
