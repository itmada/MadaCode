package madacode;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.agent.AgentRegistry;
import madacode.agent.AgentRunner;
import madacode.agent.BuiltInAgentLoader;
import madacode.services.api.ApiClient;
import madacode.tool.AgentTool;
import madacode.tool.BashTool;
import madacode.tool.FileReadTool;
import madacode.tool.GlobTool;
import madacode.tool.GrepTool;
import madacode.tool.LongRunFeatureListReplaceTool;
import madacode.tool.LongRunTaskUpdateTool;
import madacode.tool.LongRunPlanUpdateTool;
import madacode.tool.LongRunTaskSummaryUpdateTool;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.WebFetchTool;
import madacode.tool.schema.OptionalSchemaProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertRequired(new LongRunTaskSummaryUpdateTool(), "plan_summary");
        assertRequired(new LongRunFeatureListReplaceTool(), "features");
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
    void longRunPlanUpdateSchemaAcceptsLegacySummaryActionAlias() {
        JsonNode action = new LongRunPlanUpdateTool()
                .inputSchema(mapper)
                .path("properties")
                .path("action")
                .path("enum");

        assertTrue(enumContains(action, "update_task_summary"));
        assertTrue(enumContains(action, "update_plan_summary"));
    }

    @Test
    void longRunPlanUpdateSchemaUsesConcreteNestedItemContracts() {
        JsonNode schema = new LongRunFeatureListReplaceTool().inputSchema(mapper);

        JsonNode featureItems = schema.path("properties").path("features").path("items");
        assertEquals("object", featureItems.path("type").asText());
        assertTrue(featureItems.path("properties").has("id"));
        assertTrue(featureItems.path("properties").has("depends_on"));
        assertTrue(requiredContains(featureItems, "id"));
        assertTrue(requiredContains(featureItems, "category"));
        assertFalse(requiredContains(featureItems, "depends_on"));
        assertFalse(requiredContains(featureItems, "verification_steps"));
        assertFalse(requiredContains(featureItems, "passes"));
        assertFalse(featureItems.path("additionalProperties").isMissingNode());
        assertFalse(featureItems.path("additionalProperties").asBoolean(true));

        JsonNode issueItems = new madacode.tool.LongRunKnownIssuesReplaceTool()
                .inputSchema(mapper)
                .path("properties")
                .path("issues")
                .path("items");
        assertEquals("object", issueItems.path("type").asText());
        assertTrue(issueItems.path("properties").has("severity"));
        assertTrue(issueItems.path("properties").has("verification_steps"));
        assertTrue(requiredContains(issueItems, "id"));
        assertTrue(requiredContains(issueItems, "description"));
        assertTrue(requiredContains(issueItems, "severity"));
        assertFalse(requiredContains(issueItems, "status"));
        assertFalse(requiredContains(issueItems, "discovered_in"));
        assertFalse(requiredContains(issueItems, "verification_steps"));
        assertFalse(issueItems.path("additionalProperties").isMissingNode());
        assertFalse(issueItems.path("additionalProperties").asBoolean(true));
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
            assertNotNull(schema.get("additionalProperties"));
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertTrue(schema.path("properties").path("input").isMissingNode());
        }
    }

    @Test
    void generatedRecordSchemaUsesJsonPropertyNamesAndOptionalAnnotations() {
        JsonNode schema = new GeneratedSchemaTool().inputSchema(mapper);

        assertTrue(schema.path("properties").has("required_text"));
        assertTrue(schema.path("properties").has("optional_text"));
        assertTrue(requiredContains(schema, "required_text"));
        assertFalse(requiredContains(schema, "optional_text"));
        assertFalse(schema.path("properties").has("requiredText"));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
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

    private boolean enumContains(JsonNode enumValues, String value) {
        for (JsonNode enumValue : enumValues) {
            if (value.equals(enumValue.asText())) {
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

    private record GeneratedSchemaInput(
            @JsonProperty("required_text") String requiredText,
            @JsonProperty("optional_text") @OptionalSchemaProperty String optionalText) {}

    private static final class GeneratedSchemaTool implements Tool<GeneratedSchemaInput> {
        @Override
        public String name() {
            return "generated_schema";
        }

        @Override
        public String description() {
            return "Exercises generated record schema.";
        }

        @Override
        public Class<GeneratedSchemaInput> inputType() {
            return GeneratedSchemaInput.class;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ToolResult execute(GeneratedSchemaInput input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }
}
