package madacode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.tool.BashTool;
import madacode.tool.FileReadTool;
import madacode.tool.GlobTool;
import madacode.tool.GrepTool;
import madacode.tool.Tool;
import madacode.tool.validation.ToolInputValidator;
import madacode.tool.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolInputValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolInputValidator validator = new ToolInputValidator();

    @Test
    void rejectsMissingRequiredField() {
        ValidationResult result = validator.validate(new BashTool(), mapper.createObjectNode());

        assertFalse(result.valid());
        assertTrue(result.errors().contains("missing required field 'command'"));
    }

    @Test
    void rejectsWrongFieldType() {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "README.md");
        input.put("limit", "ten");

        ValidationResult result = validator.validate(new FileReadTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("field 'limit' must be integer"));
    }

    @Test
    void rejectsUnknownField() {
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "TODO");
        input.put("regex", true);

        ValidationResult result = validator.validate(new GrepTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("unknown field 'regex'"));
    }

    @Test
    void acceptsValidInput() {
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "src/**/*.java");

        ValidationResult result = validator.validate(new GlobTool(), input);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validatesAdditionalJsonSchemaTypes() {
        ObjectNode input = mapper.createObjectNode();
        input.put("ratio", 0.5);
        input.put("enabled", true);
        input.putObject("metadata").put("owner", "mada");
        input.putArray("tags").add("java").add("agent");

        ValidationResult result = validator.validate(new ComplexTool(), input);

        assertTrue(result.valid());
    }

    @Test
    void rejectsEnumAndBoundaryViolations() {
        ObjectNode input = mapper.createObjectNode();
        input.put("mode", "turbo");
        input.put("ratio", 2.0);
        input.put("name", "a");
        input.putArray("tags").add("java").add("agent").add("cli");

        ValidationResult result = validator.validate(new ComplexTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("field 'mode' must be one of")));
        assertTrue(result.errors().contains("field 'ratio' must be <= 1.0"));
        assertTrue(result.errors().contains("field 'name' length must be >= 2"));
        assertTrue(result.errors().contains("field 'tags' item count must be <= 2"));
    }

    @Test
    void rejectsArrayItemsWithWrongType() {
        ObjectNode input = mapper.createObjectNode();
        input.putArray("tags").add("java").add(21);

        ValidationResult result = validator.validate(new ComplexTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("field 'tags[1]' must be string"));
    }

    @Test
    void rejectsMissingNestedRequiredFieldInObjectArray() {
        ObjectNode input = mapper.createObjectNode();
        ObjectNode feature = input.putArray("features").addObject();
        feature.put("name", "Search");

        ValidationResult result = validator.validate(new NestedArrayTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("missing required field 'features[0].id'"));
    }

    @Test
    void rejectsUnknownNestedFieldWhenAdditionalPropertiesFalse() {
        ObjectNode input = mapper.createObjectNode();
        ObjectNode feature = input.putArray("features").addObject();
        feature.put("id", "search");
        feature.put("name", "Search");
        feature.put("extra", "nope");

        ValidationResult result = validator.validate(new NestedArrayTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("unknown field 'features[0].extra'"));
    }

    @Test
    void rejectsWrongNestedArrayItemType() {
        ObjectNode input = mapper.createObjectNode();
        ObjectNode feature = input.putArray("features").addObject();
        feature.put("id", "search");
        feature.put("name", "Search");
        feature.putArray("depends_on").add("core").add(7);

        ValidationResult result = validator.validate(new NestedArrayTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("field 'features[0].depends_on[1]' must be string"));
    }

    @Test
    void rejectsToolLimitViolations() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hi");
        input.put("timeoutSeconds", 0);

        ValidationResult result = validator.validate(new BashTool(), input);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("field 'timeoutSeconds' must be >= 1"));
    }

    @Test
    void acceptsBashTimeoutMillisAlias() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hi");
        input.put("timeout", 1000);

        ValidationResult result = validator.validate(new BashTool(), input);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsNonIntegralSchemaIntegerKeywords() {
        ValidationResult result = validator.validate(new InvalidIntegerKeywordTool(), mapper.createObjectNode());

        assertFalse(result.valid());
        assertTrue(result.errors().contains("tool schema for 'name' minLength must be an integer"));
    }

    private final class ComplexTool implements Tool<ObjectNode> {
        @Override
        public Class<ObjectNode> inputType() { return ObjectNode.class; }

        @Override
        public String name() {
            return "complex";
        }

        @Override
        public String description() {
            return "Exercises validator features.";
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

            ObjectNode mode = mapper.createObjectNode();
            mode.put("type", "string");
            mode.putArray("enum").add("safe").add("fast");
            properties.set("mode", mode);

            ObjectNode ratio = mapper.createObjectNode();
            ratio.put("type", "number");
            ratio.put("minimum", 0.0);
            ratio.put("maximum", 1.0);
            properties.set("ratio", ratio);

            ObjectNode enabled = mapper.createObjectNode();
            enabled.put("type", "boolean");
            properties.set("enabled", enabled);

            ObjectNode name = mapper.createObjectNode();
            name.put("type", "string");
            name.put("minLength", 2);
            name.put("maxLength", 12);
            properties.set("name", name);

            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("type", "object");
            properties.set("metadata", metadata);

            ObjectNode tags = mapper.createObjectNode();
            tags.put("type", "array");
            tags.put("minItems", 1);
            tags.put("maxItems", 2);
            ObjectNode items = mapper.createObjectNode();
            items.put("type", "string");
            tags.set("items", items);
            properties.set("tags", tags);

            schema.set("properties", properties);
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }

    private final class NestedArrayTool implements Tool<ObjectNode> {
        @Override
        public Class<ObjectNode> inputType() {
            return ObjectNode.class;
        }

        @Override
        public String name() {
            return "nested_array";
        }

        @Override
        public String description() {
            return "Exercises nested array/object validation.";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode featureSchema = mapper.createObjectNode();
            featureSchema.put("type", "object");
            featureSchema.put("additionalProperties", false);

            ObjectNode featureProperties = mapper.createObjectNode();
            featureProperties.set("id", stringProperty("Feature id"));
            featureProperties.set("name", stringProperty("Feature name"));

            ObjectNode dependsOn = mapper.createObjectNode();
            dependsOn.put("type", "array");
            ObjectNode dependsOnItem = mapper.createObjectNode();
            dependsOnItem.put("type", "string");
            dependsOn.set("items", dependsOnItem);
            featureProperties.set("depends_on", dependsOn);

            featureSchema.set("properties", featureProperties);
            featureSchema.putArray("required").add("id").add("name");

            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode properties = mapper.createObjectNode();
            ObjectNode features = mapper.createObjectNode();
            features.put("type", "array");
            features.set("items", featureSchema);
            properties.set("features", features);

            schema.set("properties", properties);
            schema.putArray("required").add("features");
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }

    private final class InvalidIntegerKeywordTool implements Tool<ObjectNode> {
        @Override
        public Class<ObjectNode> inputType() {
            return ObjectNode.class;
        }

        @Override
        public String name() {
            return "invalid_integer_keyword";
        }

        @Override
        public String description() {
            return "Invalid schema keyword fixture.";
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
            ObjectNode name = mapper.createObjectNode();
            name.put("type", "string");
            name.put("minLength", 1.5);
            properties.set("name", name);
            schema.set("properties", properties);
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
