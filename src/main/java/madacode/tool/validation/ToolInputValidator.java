package madacode.tool.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.tool.Tool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ToolInputValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "string", "integer", "number", "boolean", "array", "object");

    private final ObjectMapper mapper;

    public ToolInputValidator() {
        this(new ObjectMapper());
    }

    public ToolInputValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ValidationResult validate(Tool<?> tool, ObjectNode input) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");

        ObjectNode schema = tool.inputSchema(mapper);
        List<String> errors = new ArrayList<>();

        validateSchemaStructure(schema, "", true, errors);
        if (!errors.isEmpty()) {
            return ValidationResult.invalid(errors);
        }

        validateObjectValue(input, schema, "", true, errors);

        return errors.isEmpty()
                ? ValidationResult.ok()
                : ValidationResult.invalid(errors);
    }

    private void validateSchemaStructure(JsonNode schema, String path, boolean topLevel, List<String> errors) {
        if (!schema.isObject()) {
            errors.add(schemaError(path, topLevel, "must be an object"));
            return;
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            errors.add(schemaError(path, topLevel, "type must be a string"));
            return;
        }

        String type = typeNode.asText();
        if (!SUPPORTED_TYPES.contains(type)) {
            errors.add(schemaError(path, topLevel,
                    "type must be one of " + SUPPORTED_TYPES));
            return;
        }

        validateSchemaEnum(schema, path, topLevel, errors);
        validateSchemaNumber(schema.get("minimum"), path, topLevel, "minimum", errors);
        validateSchemaNumber(schema.get("maximum"), path, topLevel, "maximum", errors);
        validateSchemaInteger(schema.get("minLength"), path, topLevel, "minLength", errors);
        validateSchemaInteger(schema.get("maxLength"), path, topLevel, "maxLength", errors);
        validateSchemaInteger(schema.get("minItems"), path, topLevel, "minItems", errors);
        validateSchemaInteger(schema.get("maxItems"), path, topLevel, "maxItems", errors);

        switch (type) {
            case "object" -> validateObjectSchemaStructure(schema, path, topLevel, errors);
            case "array" -> validateArraySchemaStructure(schema, path, topLevel, errors);
            default -> {
            }
        }
    }

    private void validateObjectSchemaStructure(JsonNode schema, String path, boolean topLevel, List<String> errors) {
        JsonNode properties = schema.get("properties");
        if (topLevel) {
            if (properties == null || !properties.isObject()) {
                errors.add("tool schema properties must be an object");
                return;
            }
        } else if (properties != null && !properties.isObject()) {
            errors.add(schemaError(path, false, "properties must be an object"));
            return;
        }

        JsonNode required = schema.get("required");
        if (required != null && !required.isArray()) {
            errors.add(schemaError(path, topLevel, "required must be an array"));
        } else if (required != null) {
            for (JsonNode requiredField : required) {
                if (!requiredField.isTextual()) {
                    errors.add(schemaError(path, topLevel, "required entries must be strings"));
                    break;
                }
            }
        }

        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && !additionalProperties.isBoolean()) {
            errors.add(schemaError(path, topLevel, "additionalProperties must be boolean"));
        }

        if (properties != null && properties.isObject()) {
            Iterator<String> propertyNames = properties.fieldNames();
            while (propertyNames.hasNext()) {
                String fieldName = propertyNames.next();
                validateSchemaStructure(properties.get(fieldName), appendObjectPath(path, fieldName), false, errors);
            }
        }
    }

    private void validateArraySchemaStructure(JsonNode schema, String path, boolean topLevel, List<String> errors) {
        JsonNode items = schema.get("items");
        if (items == null) {
            return;
        }
        if (!items.isObject()) {
            errors.add(schemaError(path, topLevel, "items must be an object"));
            return;
        }
        validateSchemaStructure(items, appendArrayPath(path), false, errors);
    }

    private void validateSchemaEnum(JsonNode schema, String path, boolean topLevel, List<String> errors) {
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && !enumValues.isArray()) {
            errors.add(schemaError(path, topLevel, "enum must be an array"));
        }
    }

    private void validateSchemaNumber(
            JsonNode value,
            String path,
            boolean topLevel,
            String keyword,
            List<String> errors
    ) {
        if (value != null && !value.isNumber()) {
            errors.add(schemaError(path, topLevel, keyword + " must be a number"));
        }
    }

    private void validateSchemaInteger(
            JsonNode value,
            String path,
            boolean topLevel,
            String keyword,
            List<String> errors
    ) {
        if (value != null && !value.isIntegralNumber()) {
            errors.add(schemaError(path, topLevel, keyword + " must be an integer"));
        }
    }

    private void validateValue(JsonNode value, JsonNode schema, String path, boolean topLevel, List<String> errors) {
        String expectedType = schema.path("type").asText();
        if (!matchesType(value, expectedType)) {
            errors.add(typeError(path, expectedType));
            return;
        }

        validateEnum(path, schema, value, errors);
        validateBounds(path, schema, value, errors);

        switch (expectedType) {
            case "object" -> validateObjectValue((ObjectNode) value, schema, path, topLevel, errors);
            case "array" -> validateArrayValue(value, schema, path, errors);
            default -> {
            }
        }
    }

    private void validateObjectValue(
            ObjectNode input,
            JsonNode schema,
            String path,
            boolean topLevel,
            List<String> errors
    ) {
        JsonNode properties = schema.get("properties");
        validateRequiredFields(schema.get("required"), input, path, errors);

        boolean rejectUnknownFields = topLevel || hasAdditionalPropertiesFalse(schema);
        Iterator<String> fieldNames = input.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode propertySchema = properties == null ? null : properties.get(fieldName);
            if (propertySchema == null) {
                if (rejectUnknownFields) {
                    errors.add("unknown field '" + appendObjectPath(path, fieldName) + "'");
                }
                continue;
            }

            JsonNode value = input.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            validateValue(value, propertySchema, appendObjectPath(path, fieldName), false, errors);
        }
    }

    private boolean matchesType(JsonNode value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
    }

    private void validateRequiredFields(JsonNode required, ObjectNode input, String path, List<String> errors) {
        if (required == null || !required.isArray()) {
            return;
        }
        for (JsonNode requiredField : required) {
            String fieldName = requiredField.asText();
            JsonNode value = input.get(fieldName);
            if (value == null || value.isNull()) {
                errors.add("missing required field '" + appendObjectPath(path, fieldName) + "'");
            }
        }
    }

    private void validateEnum(String fieldPath, JsonNode propertySchema, JsonNode value, List<String> errors) {
        JsonNode allowedValues = propertySchema.path("enum");
        if (!allowedValues.isArray()) {
            return;
        }
        for (JsonNode allowedValue : allowedValues) {
            if (allowedValue.equals(value)) {
                return;
            }
        }
        errors.add(fieldError(fieldPath) + " must be one of " + allowedValues);
    }

    private void validateBounds(String fieldPath, JsonNode propertySchema, JsonNode value, List<String> errors) {
        if (value.isNumber()) {
            validateNumericBounds(fieldPath, propertySchema, value, errors);
        }
        if (value.isTextual()) {
            validateLengthBounds(fieldPath, propertySchema, value.asText().length(), errors);
        }
        if (value.isArray()) {
            validateItemCountBounds(fieldPath, propertySchema, value.size(), errors);
        }
    }

    private void validateNumericBounds(String fieldPath, JsonNode propertySchema, JsonNode value, List<String> errors) {
        double number = value.asDouble();
        JsonNode minimum = propertySchema.get("minimum");
        if (minimum != null && minimum.isNumber() && number < minimum.asDouble()) {
            errors.add(fieldError(fieldPath) + " must be >= " + minimum);
        }
        JsonNode maximum = propertySchema.get("maximum");
        if (maximum != null && maximum.isNumber() && number > maximum.asDouble()) {
            errors.add(fieldError(fieldPath) + " must be <= " + maximum);
        }
    }

    private void validateLengthBounds(String fieldPath, JsonNode propertySchema, int length, List<String> errors) {
        JsonNode minLength = propertySchema.get("minLength");
        if (minLength != null && minLength.isIntegralNumber() && length < minLength.asInt()) {
            errors.add(fieldError(fieldPath) + " length must be >= " + minLength.asInt());
        }
        JsonNode maxLength = propertySchema.get("maxLength");
        if (maxLength != null && maxLength.isIntegralNumber() && length > maxLength.asInt()) {
            errors.add(fieldError(fieldPath) + " length must be <= " + maxLength.asInt());
        }
    }

    private void validateItemCountBounds(String fieldPath, JsonNode propertySchema, int size, List<String> errors) {
        JsonNode minItems = propertySchema.get("minItems");
        if (minItems != null && minItems.isIntegralNumber() && size < minItems.asInt()) {
            errors.add(fieldError(fieldPath) + " item count must be >= " + minItems.asInt());
        }
        JsonNode maxItems = propertySchema.get("maxItems");
        if (maxItems != null && maxItems.isIntegralNumber() && size > maxItems.asInt()) {
            errors.add(fieldError(fieldPath) + " item count must be <= " + maxItems.asInt());
        }
    }

    private void validateArrayValue(JsonNode value, JsonNode propertySchema, String path, List<String> errors) {
        if (!value.isArray()) {
            return;
        }
        JsonNode itemSchema = propertySchema.get("items");
        if (itemSchema == null || !itemSchema.isObject()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            JsonNode itemValue = value.get(i);
            if (itemValue == null || itemValue.isNull()) {
                continue;
            }
            validateValue(itemValue, itemSchema, appendIndexedPath(path, i), false, errors);
        }
    }

    private boolean hasAdditionalPropertiesFalse(JsonNode schema) {
        JsonNode additionalProperties = schema.get("additionalProperties");
        return additionalProperties != null && additionalProperties.isBoolean() && !additionalProperties.asBoolean();
    }

    private String typeError(String fieldPath, String expectedType) {
        return fieldError(fieldPath) + " must be " + expectedType;
    }

    private String fieldError(String fieldPath) {
        return "field '" + fieldPath + "'";
    }

    private String schemaError(String path, boolean topLevel, String message) {
        if (topLevel || path.isBlank()) {
            return "tool schema " + message;
        }
        return "tool schema for '" + path + "' " + message;
    }

    private String appendObjectPath(String path, String fieldName) {
        return path.isBlank() ? fieldName : path + "." + fieldName;
    }

    private String appendArrayPath(String path) {
        return path + "[]";
    }

    private String appendIndexedPath(String path, int index) {
        return path + "[" + index + "]";
    }
}
