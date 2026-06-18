package madacode.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolCall;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.permission.PermissionGate;
import madacode.tool.BashTool;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.validation.ToolInputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolContractTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownToolReturnsTypedFailureResult() {
        ToolResult result = executor(null).execute(
                new ToolCall("toolu_1", "missing_tool", mapper.createObjectNode()),
                context());

        assertFalse(result.success());
        assertTrue(result.output().contains("unknown tool \"missing_tool\""));
    }

    @Test
    void schemaValidationFailureReturnsTypedFailureResult() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hi");
        input.put("timeoutSeconds", "fast");

        ToolResult result = executor(new BashTool()).execute(
                new ToolCall("toolu_2", "bash", input),
                context());

        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid tool input for bash"));
        assertTrue(result.output().contains("field 'timeoutSeconds' must be integer"));
    }

    @Test
    void coercionMismatchReturnsTypedFailureInsteadOfCrashing() {
        SchemaMismatchTool tool = new SchemaMismatchTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("count", "not-a-number");

        ToolResult result = executor(tool).execute(
                new ToolCall("toolu_3", tool.name(), input),
                loadedContext(tool.name()));

        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid tool input for " + tool.name()));
        assertFalse(result.output().contains("Tool execution failed"));
    }

    private ToolExecutor executor(Tool<?> tool) {
        ToolRegistry registry = new ToolRegistry();
        if (tool != null) {
            registry.register(tool);
        }
        return new ToolExecutor(registry, new ToolInputValidator(), PermissionGate.permissive(), null, mapper);
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, new ConversationSession(tempDir));
    }

    private ToolUseContext loadedContext(String toolName) {
        ConversationSession session = new ConversationSession(tempDir);
        session.loadDeferredTool(toolName);
        return new ToolUseContext(tempDir, session);
    }

    private final class SchemaMismatchTool implements Tool<SchemaMismatchInput> {
        @Override
        public String name() {
            return "schema_mismatch";
        }

        @Override
        public String description() {
            return "Exercises coercion failures after schema validation passes.";
        }

        @Override
        public Class<SchemaMismatchInput> inputType() {
            return SchemaMismatchInput.class;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = mapper.createObjectNode();
            ObjectNode count = mapper.createObjectNode();
            count.put("type", "string");
            properties.set("count", count);
            schema.set("properties", properties);
            schema.putArray("required").add("count");
            return schema;
        }

        @Override
        public ToolResult execute(SchemaMismatchInput input, ToolUseContext context) {
            throw new AssertionError("coercion mismatch should fail before execute");
        }
    }

    private record SchemaMismatchInput(Integer count) {
    }
}
