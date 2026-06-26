package madacode.tool.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal first-party JSON-schema generator for tool input records.
 *
 * <p>This intentionally supports only the shapes used by tool contracts:
 * records, lists, enums, strings, booleans, integers, numbers, and nested
 * combinations of those. Tools that need richer or hand-curated schemas can
 * still override {@code inputSchema()} manually.
 */
public final class RecordSchemaGenerator {

    private final ObjectMapper mapper;

    public RecordSchemaGenerator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ObjectNode objectSchemaFor(Class<?> recordType) {
        Objects.requireNonNull(recordType, "recordType");
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException("Schema generation requires a record type: " + recordType.getName());
        }
        return objectSchemaForRecord(recordType);
    }

    private ObjectNode schemaFor(Type type) {
        if (type instanceof Class<?> clazz) {
            return schemaForClass(clazz);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return schemaForParameterizedType(parameterizedType);
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length == 1) {
                return schemaFor(upperBounds[0]);
            }
        }
        throw new IllegalArgumentException("Unsupported schema type: " + type.getTypeName());
    }

    private ObjectNode schemaForClass(Class<?> type) {
        if (type.isRecord()) {
            return objectSchemaForRecord(type);
        }
        if (type.isEnum()) {
            return enumSchema(type);
        }
        if (type == String.class || type == CharSequence.class) {
            return simpleType("string");
        }
        if (type == boolean.class || type == Boolean.class) {
            return simpleType("boolean");
        }
        if (isIntegerType(type)) {
            return simpleType("integer");
        }
        if (isNumberType(type)) {
            return simpleType("number");
        }
        throw new IllegalArgumentException("Unsupported schema class: " + type.getName());
    }

    private ObjectNode schemaForParameterizedType(ParameterizedType type) {
        Type rawType = type.getRawType();
        if (!(rawType instanceof Class<?> rawClass)) {
            throw new IllegalArgumentException("Unsupported parameterized schema type: " + type.getTypeName());
        }
        if (List.class.isAssignableFrom(rawClass)) {
            Type[] typeArguments = type.getActualTypeArguments();
            if (typeArguments.length != 1) {
                throw new IllegalArgumentException("List schema requires exactly one type argument: " + type.getTypeName());
            }
            ObjectNode schema = simpleType("array");
            schema.set("items", schemaFor(typeArguments[0]));
            return schema;
        }
        if (Optional.class == rawClass) {
            Type[] typeArguments = type.getActualTypeArguments();
            if (typeArguments.length != 1) {
                throw new IllegalArgumentException(
                        "Optional schema requires exactly one type argument: " + type.getTypeName());
            }
            return schemaFor(typeArguments[0]);
        }
        throw new IllegalArgumentException("Unsupported parameterized schema type: " + type.getTypeName());
    }

    private ObjectNode objectSchemaForRecord(Class<?> recordType) {
        ObjectNode schema = simpleType("object");
        ObjectNode properties = mapper.createObjectNode();
        ArrayNode required = mapper.createArrayNode();
        List<String> requiredNames = new ArrayList<>();

        for (RecordComponent component : recordType.getRecordComponents()) {
            properties.set(component.getName(), schemaFor(component.getGenericType()));
            if (!isOptional(component)) {
                requiredNames.add(component.getName());
            }
        }

        requiredNames.forEach(required::add);
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode enumSchema(Class<?> enumType) {
        ObjectNode schema = simpleType("string");
        ArrayNode values = mapper.createArrayNode();
        for (Object constant : enumType.getEnumConstants()) {
            values.add(((Enum<?>) constant).name());
        }
        schema.set("enum", values);
        return schema;
    }

    private ObjectNode simpleType(String type) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", type);
        return schema;
    }

    private boolean isOptional(RecordComponent component) {
        Type genericType = component.getGenericType();
        if (genericType instanceof ParameterizedType parameterizedType) {
            return parameterizedType.getRawType() == Optional.class;
        }
        return false;
    }

    private static boolean isIntegerType(Class<?> type) {
        return type == byte.class
                || type == Byte.class
                || type == short.class
                || type == Short.class
                || type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class
                || type == BigInteger.class;
    }

    private static boolean isNumberType(Class<?> type) {
        return type == float.class
                || type == Float.class
                || type == double.class
                || type == Double.class
                || type == BigDecimal.class;
    }
}
