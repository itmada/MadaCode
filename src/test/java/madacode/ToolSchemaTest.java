package madacode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.agent.BuiltInAgentLoader;
import madacode.services.api.ApiClient;
import madacode.tool.AgentTool;
import madacode.tool.BashTool;
import madacode.tool.FileReadTool;
import madacode.tool.GlobTool;
import madacode.tool.GrepTool;
import madacode.tool.LongRunTaskUpdateTool;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.WebFetchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolsExposeConcreteRequiredInputFields() {
        assertRequired(new BashTool(), "command");
        assertRequired(new FileReadTool(), "path");
        assertRequired(new GlobTool(), "pattern");
        assertRequired(new GrepTool(), "pattern");
        assertRequired(new WebFetchTool(), "url");
        assertRequired(new LongRunTaskUpdateTool(), "action");
        assertRequired(agentTool(), "description");
        assertRequired(agentTool(), "prompt");
    }

    @Test
    void agentToolSubagentTypeIsOptional() {
        JsonNode schema = agentTool().inputSchema(mapper);
        assertTrue(schema.path("properties").has("subagent_type"));
        assertFalse(requiredContains(schema, "subagent_type"));
    }

    @Test
    void webFetchPromptIsOptional() {
        JsonNode schema = new WebFetchTool().inputSchema(mapper);
        assertTrue(schema.path("properties").has("prompt"));
        assertFalse(requiredContains(schema, "prompt"));
    }

    @Test
    void toolSchemasDoNotUseLegacyInputField() {
        List<Tool<?>> tools = List.of(
                new BashTool(),
                new FileReadTool(),
                new GlobTool(),
                new GrepTool(),
                new WebFetchTool(),
                new LongRunTaskUpdateTool(),
                agentTool());

        for (Tool tool : tools) {
            JsonNode schema = tool.inputSchema(mapper);
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("properties").isObject());
            assertTrue(schema.path("required").isArray());
            assertTrue(schema.path("properties").path("input").isMissingNode());
        }
    }

    private void assertRequired(Tool tool, String fieldName) {
        JsonNode schema = tool.inputSchema(mapper);
        assertTrue(schema.path("properties").has(fieldName));
        assertTrue(requiredContains(schema, fieldName));
    }

    private boolean requiredContains(JsonNode schema, String fieldName) {
        for (JsonNode required : schema.path("required")) {
            if (fieldName.equals(required.asText())) {
                return true;
            }
        }
        return false;
    }

    private AgentTool agentTool() {
        ApiClient fakeApiClient = (messages, systemPrompt, tools, sink, cancellationToken) ->
                new ApiClient.ApiResponse("", List.of());
        return new AgentTool(
                new AgentRunner(new ToolRegistry(), fakeApiClient, madacode.permission.PermissionGate.permissive()),
                AgentRegistry.loaded(new BuiltInAgentLoader()));
    }
}
