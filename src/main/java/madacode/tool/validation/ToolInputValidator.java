package madacode.tool.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.tool.Tool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class ToolInputValidator {

    private final ObjectMapper mapper;

    public ToolInputValidator() {
        this(new ObjectMapper());
    }

    ToolInputValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ValidationResult validate(Tool<?> tool, ObjectNode input) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");

        ObjectNode schema = tool.inputSchema(mapper);
        List<String> errors = new ArrayList<>();

        if (!"object".equals(schema.path("type").asText())) {
            errors.add("tool schema must have type 'object'");
            return ValidationResult.invalid(errors);
        }

        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            errors.add("tool schema properties must be an object");
            return ValidationResult.invalid(errors);
        }

        validateRequiredFields(schema.path("required"), input, errors);
        validateUnknownFields(properties, input, errors);
        validateFieldTypes(properties, input, errors);

        return errors.isEmpty()
                ? ValidationResult.ok()
                : ValidationResult.invalid(errors);
    }

    private void validateRequiredFields(JsonNode required, ObjectNode input, List<String> errors) {
        if (!required.isArray()) {
            return;
        }
        for (JsonNode requiredField : required) {
            String fieldName = requiredField.asText();
            JsonNode value = input.get(fieldName);
            if (value == null || value.isNull()) {
                errors.add("missing required field '" + fieldName + "'");
            }
        }
    }

    private void validateUnknownFields(JsonNode properties, ObjectNode input, List<String> errors) {
        Iterator<String> fieldNames = input.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!properties.has(fieldName)) {
                errors.add("unknown field '" + fieldName + "'");
            }
        }
    }

    private void validateFieldTypes(JsonNode properties, ObjectNode input, List<String> errors) {
        Iterator<String> propertyNames = properties.fieldNames();
        while (propertyNames.hasNext()) {
            String fieldName = propertyNames.next();
            JsonNode value = input.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }

            String expectedType = properties.path(fieldName).path("type").asText();
            if (!matchesType(value, expectedType)) {
                errors.add("field '" + fieldName + "' must be " + expectedType);
                continue;
            }

            validateEnum(fieldName, properties.path(fieldName), value, errors);
            validateBounds(fieldName, properties.path(fieldName), value, errors);
            validateArrayItems(fieldName, properties.path(fieldName), value, errors);
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

    private void validateEnum(String fieldName, JsonNode propertySchema, JsonNode value, List<String> errors) {
        JsonNode allowedValues = propertySchema.path("enum");
        if (!allowedValues.isArray()) {
            return;
        }
        for (JsonNode allowedValue : allowedValues) {
            if (allowedValue.equals(value)) {
                return;
            }
        }
        errors.add("field '" + fieldName + "' must be one of " + allowedValues);
    }

    private void validateBounds(String fieldName, JsonNode propertySchema, JsonNode value, List<String> errors) {
        if (value.isNumber()) {
            validateNumericBounds(fieldName, propertySchema, value, errors);
        }
        if (value.isTextual()) {
            validateLengthBounds(fieldName, propertySchema, value.asText().length(), errors);
        }
        if (value.isArray()) {
            validateItemCountBounds(fieldName, propertySchema, value.size(), errors);
        }
    }

    private void validateNumericBounds(String fieldName, JsonNode propertySchema, JsonNode value, List<String> errors) {
        double number = value.asDouble();
        JsonNode minimum = propertySchema.get("minimum");
        if (minimum != null && minimum.isNumber() && number < minimum.asDouble()) {
            errors.add("field '" + fieldName + "' must be >= " + minimum);
        }
        JsonNode maximum = propertySchema.get("maximum");
        if (maximum != null && maximum.isNumber() && number > maximum.asDouble()) {
            errors.add("field '" + fieldName + "' must be <= " + maximum);
        }
    }

    private void validateLengthBounds(String fieldName, JsonNode propertySchema, int length, List<String> errors) {
        JsonNode minLength = propertySchema.get("minLength");
        if (minLength != null && minLength.canConvertToInt() && length < minLength.asInt()) {
            errors.add("field '" + fieldName + "' length must be >= " + minLength.asInt());
        }
        JsonNode maxLength = propertySchema.get("maxLength");
        if (maxLength != null && maxLength.canConvertToInt() && length > maxLength.asInt()) {
            errors.add("field '" + fieldName + "' length must be <= " + maxLength.asInt());
        }
    }

    private void validateItemCountBounds(String fieldName, JsonNode propertySchema, int size, List<String> errors) {
        JsonNode minItems = propertySchema.get("minItems");
        if (minItems != null && minItems.canConvertToInt() && size < minItems.asInt()) {
            errors.add("field '" + fieldName + "' item count must be >= " + minItems.asInt());
        }
        JsonNode maxItems = propertySchema.get("maxItems");
        if (maxItems != null && maxItems.canConvertToInt() && size > maxItems.asInt()) {
            errors.add("field '" + fieldName + "' item count must be <= " + maxItems.asInt());
        }
    }

    private void validateArrayItems(String fieldName, JsonNode propertySchema, JsonNode value, List<String> errors) {
        if (!value.isArray()) {
            return;
        }
        String itemType = propertySchema.path("items").path("type").asText("");
        if (itemType.isBlank()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            if (!matchesType(value.get(i), itemType)) {
                errors.add("field '" + fieldName + "' item " + i + " must be " + itemType);
            }
        }
    }
}
