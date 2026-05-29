package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void findsToolByCanonicalNamesAndLegacyAliases() {
        ToolRegistry registry = new ToolRegistry();
        Tool<?> planCreate = new StubTool("plan_create");
        Tool<?> askUserQuestion = new StubTool("ask_user_question");
        Tool<?> skill = new StubTool("skill");
        Tool<?> webFetch = new StubTool("web_fetch");

        registry.register(planCreate);
        registry.register(askUserQuestion);
        registry.register(skill);
        registry.register(webFetch);

        assertSameName(registry, "plan_create", "plan_create");
        assertSameName(registry, "PlanCreate", "plan_create");
        assertSameName(registry, "plan-create", "plan_create");

        assertSameName(registry, "ask_user_question", "ask_user_question");
        assertSameName(registry, "AskUserQuestion", "ask_user_question");
        assertSameName(registry, "ask-user-question", "ask_user_question");

        assertSameName(registry, "skill", "skill");
        assertSameName(registry, "Skill", "skill");

        assertSameName(registry, "web_fetch", "web_fetch");
        assertSameName(registry, "webfetch", "web_fetch");
        assertSameName(registry, "WebFetch", "web_fetch");
    }

    @Test
    void removeByAliasRemovesCanonicalToolName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("plan_update"));

        registry.remove("PlanUpdate");

        assertTrue(registry.find("plan_update").isEmpty());
        assertTrue(registry.find("PlanUpdate").isEmpty());
    }

    @Test
    void unknownToolLookupReturnsEmptyForCanonicalAndAliasNames() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("plan_list"));

        assertTrue(registry.find("does_not_exist").isEmpty());
        assertFalse(registry.find("plan_list").isEmpty());
    }

    private static void assertSameName(ToolRegistry registry, String query, String expectedName) {
        Tool<?> tool = registry.find(query).orElseThrow();
        assertEquals(expectedName, tool.name(), "lookup " + query + " should resolve to " + expectedName);
    }

    private static final class StubTool implements Tool<ObjectNode> {
        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public Class<ObjectNode> inputType() {
            return ObjectNode.class;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }
}
