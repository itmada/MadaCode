package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ToolSchemas {

    private ToolSchemas() {
    }

    static ObjectNode objectSchema(ObjectMapper mapper, ObjectNode properties, String... requiredNames) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        for (String requiredName : requiredNames) {
            required.add(requiredName);
        }
        schema.set("required", required);
        return schema;
    }

    static ObjectNode stringProperty(ObjectMapper mapper, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    static ObjectNode integerProperty(ObjectMapper mapper, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    static ObjectNode integerProperty(ObjectMapper mapper, String description, int minimum, int maximum) {
        ObjectNode property = integerProperty(mapper, description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }

    static ObjectNode booleanProperty(ObjectMapper mapper, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    static ObjectNode arrayProperty(ObjectMapper mapper, String description, ObjectNode items) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "array");
        property.put("description", description);
        property.set("items", items);
        return property;
    }

    static ObjectNode stringEnumProperty(ObjectMapper mapper, String description, String... values) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        ArrayNode enumValues = mapper.createArrayNode();
        for (String v : values) {
            enumValues.add(v);
        }
        property.set("enum", enumValues);
        return property;
    }

    static ObjectNode stringItem(ObjectMapper mapper) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "string");
        return item;
    }

    static ObjectNode objectItem(ObjectMapper mapper) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        return item;
    }
}
