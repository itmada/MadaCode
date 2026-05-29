package madacode.tool;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Centralised converter from the model's raw JSON tool_use input
 * ({@link ObjectNode}) into the tool's strongly-typed input record.
 *
 * <p>Pulled out of {@link ToolExecutor} so the orchestrator can also
 * deserialise once before deciding concurrency without coupling to the
 * executor's full lifecycle.
 *
 * <p>Schema-level validation happens beforehand in {@link ToolInputValidator}
 * — by the time we coerce here, missing required fields, type mismatches,
 * and unknown fields have already been rejected. Any failure at this stage
 * therefore indicates a record/schema drift, not a model error.
 */
public final class ToolInputCoercion {

    private ToolInputCoercion() {}

    /**
     * Deserialises {@code input} into the tool's declared input type.
     *
     * @throws ToolInputCoercionException if Jackson can't construct an
     *         instance of {@code tool.inputType()} from {@code input}.
     */
    @SuppressWarnings("unchecked")
    public static <I> I coerce(Tool<I> tool, ObjectNode input, ObjectMapper mapper) {
        try {
            return mapper.treeToValue(input, tool.inputType());
        } catch (JsonMappingException e) {
            throw new ToolInputCoercionException(
                    "Tool '" + tool.name() + "' input deserialisation failed: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new ToolInputCoercionException(
                    "Tool '" + tool.name() + "' input deserialisation failed: " + e.getMessage(), e);
        }
    }

    /** Type-erased convenience that hides the wildcard cast at the call site. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object coerceUnchecked(Tool<?> tool, ObjectNode input, ObjectMapper mapper) {
        return coerce((Tool) tool, input, mapper);
    }

    public static final class ToolInputCoercionException extends RuntimeException {
        public ToolInputCoercionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
