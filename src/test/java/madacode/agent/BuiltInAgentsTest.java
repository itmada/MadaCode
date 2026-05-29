package madacode.agent;

import static org.junit.jupiter.api.Assertions.*;

import madacode.core.QueryEngine;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BuiltInAgents")
class BuiltInAgentsTest {

    @Nested
    @DisplayName("getAll")
    class GetAll {

        @Test
        @DisplayName("returns exactly the 3 built-in agents")
        void returnsExactlyThreeBuiltInAgents() {
            List<AgentDefinition> all = BuiltInAgents.getAll();

            assertEquals(3, all.size(), () -> "expected 3 built-ins but got " + all.size());
        }

        @Test
        @DisplayName("contains explorer, planner, and general agent types")
        void containsAllThreeAgentTypes() {
            List<String> types = BuiltInAgents.getAll().stream()
                    .map(AgentDefinition::agentType)
                    .toList();

            assertTrue(types.contains("explorer"), "missing explorer");
            assertTrue(types.contains("planner"), "missing planner");
            assertTrue(types.contains("general"), "missing general");
        }

        @Test
        @DisplayName("getName returns same value as agentType for backward compatibility")
        void nameEqualsAgentType() {
            for (AgentDefinition def : BuiltInAgents.getAll()) {
                assertEquals(def.agentType(), def.name(),
                        () -> "name() should equal agentType() for " + def.agentType());
            }
        }
    }

    @Nested
    @DisplayName("findByType")
    class FindByType {

        @Test
        @DisplayName("finds explorer by exact type")
        void findsExplorerByExactType() {
            Optional<AgentDefinition> result = BuiltInAgents.findByType("explorer");

            assertTrue(result.isPresent());
            assertEquals("explorer", result.get().agentType());
        }

        @Test
        @DisplayName("finds planner by exact type")
        void findsPlannerByExactType() {
            Optional<AgentDefinition> result = BuiltInAgents.findByType("planner");

            assertTrue(result.isPresent());
            assertEquals("planner", result.get().agentType());
        }

        @Test
        @DisplayName("finds general by exact type")
        void findsGeneralByExactType() {
            Optional<AgentDefinition> result = BuiltInAgents.findByType("general");

            assertTrue(result.isPresent());
            assertEquals("general", result.get().agentType());
        }

        @Test
        @DisplayName("case-insensitive lookup")
        void caseInsensitiveLookup() {
            assertTrue(BuiltInAgents.findByType("EXPLORER").isPresent());
            assertTrue(BuiltInAgents.findByType("Explorer").isPresent());
            assertTrue(BuiltInAgents.findByType("PLANNER").isPresent());
            assertTrue(BuiltInAgents.findByType("Planner").isPresent());
            assertTrue(BuiltInAgents.findByType("GENERAL").isPresent());
            assertTrue(BuiltInAgents.findByType("General").isPresent());
        }

        @Test
        @DisplayName("returns Optional.empty for unknown type")
        void returnsEmptyForUnknownType() {
            Optional<AgentDefinition> result = BuiltInAgents.findByType("unknown");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns Optional.empty for null")
        void returnsEmptyForNull() {
            Optional<AgentDefinition> result = BuiltInAgents.findByType(null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns Optional.empty for blank string")
        void returnsEmptyForBlank() {
            assertTrue(BuiltInAgents.findByType("").isEmpty());
            assertTrue(BuiltInAgents.findByType("   ").isEmpty());
        }
    }

    @Nested
    @DisplayName("agent field validation")
    class AgentFieldValidation {

        @Test
        @DisplayName("every agent has nonblank description")
        void everyAgentHasNonblankDescription() {
            for (AgentDefinition def : BuiltInAgents.getAll()) {
                assertNotNull(def.description(), def.agentType() + " description is null");
                assertFalse(def.description().isBlank(),
                        def.agentType() + " description is blank");
            }
        }

        @Test
        @DisplayName("every agent has nonblank whenToUse")
        void everyAgentHasNonblankWhenToUse() {
            for (AgentDefinition def : BuiltInAgents.getAll()) {
                assertNotNull(def.whenToUse(), def.agentType() + " whenToUse is null");
                assertFalse(def.whenToUse().isBlank(),
                        def.agentType() + " whenToUse is blank");
            }
        }

        @Test
        @DisplayName("every agent has nonblank systemPrompt")
        void everyAgentHasNonblankSystemPrompt() {
            for (AgentDefinition def : BuiltInAgents.getAll()) {
                assertNotNull(def.systemPrompt(), def.agentType() + " systemPrompt is null");
                assertFalse(def.systemPrompt().isBlank(),
                        def.agentType() + " systemPrompt is blank");
            }
        }

        @Test
        @DisplayName("every agent has positive maxIterations")
        void everyAgentHasPositiveMaxIterations() {
            for (AgentDefinition def : BuiltInAgents.getAll()) {
                assertTrue(def.maxIterations() > 0,
                        () -> def.agentType() + " maxIterations should be > 0, was " + def.maxIterations());
            }
        }

        @Test
        @DisplayName("every agent has positive maxToolCalls")
        void everyAgentHasPositiveMaxToolCalls() {
            for (AgentDefinition def : BuiltInAgents.getAll()) {
                assertTrue(def.maxToolCalls() > 0,
                        () -> def.agentType() + " maxToolCalls should be > 0, was " + def.maxToolCalls());
            }
        }
    }

    @Nested
    @DisplayName("tool restrictions")
    class ToolRestrictions {

        @Test
        @DisplayName("explorer does not allow bash or agent")
        void explorerDisallowsBashAndAgent() {
            AgentDefinition explorer = BuiltInAgents.explorer();

            assertFalse(explorer.allowedTools().contains("bash"),
                    "explorer should not allow bash");
            assertFalse(explorer.allowedTools().contains("agent"),
                    "explorer should not allow agent");
            assertTrue(explorer.disallowedTools().contains("bash"));
            assertTrue(explorer.disallowedTools().contains("agent"));
        }

        @Test
        @DisplayName("explorer allows read-only tools")
        void explorerAllowsReadOnlyTools() {
            AgentDefinition explorer = BuiltInAgents.explorer();

            assertTrue(explorer.allowedTools().contains("file_read"));
            assertTrue(explorer.allowedTools().contains("glob"));
            assertTrue(explorer.allowedTools().contains("grep"));
        }

        @Test
        @DisplayName("planner does not allow bash or agent")
        void plannerDisallowsBashAndAgent() {
            AgentDefinition planner = BuiltInAgents.planner();

            assertFalse(planner.allowedTools().contains("bash"),
                    "planner should not allow bash");
            assertFalse(planner.allowedTools().contains("agent"),
                    "planner should not allow agent");
            assertTrue(planner.disallowedTools().contains("bash"));
            assertTrue(planner.disallowedTools().contains("agent"));
        }

        @Test
        @DisplayName("planner allows read-only tools")
        void plannerAllowsReadOnlyTools() {
            AgentDefinition planner = BuiltInAgents.planner();

            assertTrue(planner.allowedTools().contains("file_read"));
            assertTrue(planner.allowedTools().contains("glob"));
            assertTrue(planner.allowedTools().contains("grep"));
        }

        @Test
        @DisplayName("general allows bash but not agent")
        void generalAllowsBashButNotAgent() {
            AgentDefinition general = BuiltInAgents.general();

            assertTrue(general.allowedTools().contains("bash"),
                    "general should allow bash");
            assertFalse(general.allowedTools().contains("agent"),
                    "general should not allow agent");
            assertTrue(general.disallowedTools().contains("agent"));
        }
    }

    @Nested
    @DisplayName("budget values")
    class BudgetValues {

        @Test
        @DisplayName("explorer budgets match spec")
        void explorerBudgets() {
            AgentDefinition explorer = BuiltInAgents.explorer();
            assertEquals(QueryEngine.DEFAULT_MAX_ITERATIONS, explorer.maxIterations());
            assertEquals(20, explorer.maxToolCalls());
        }

        @Test
        @DisplayName("planner budgets match spec")
        void plannerBudgets() {
            AgentDefinition planner = BuiltInAgents.planner();
            assertEquals(4, planner.maxIterations());
            assertEquals(12, planner.maxToolCalls());
        }

        @Test
        @DisplayName("general budgets match spec")
        void generalBudgets() {
            AgentDefinition general = BuiltInAgents.general();
            assertEquals(8, general.maxIterations());
            assertEquals(30, general.maxToolCalls());
        }
    }
}
