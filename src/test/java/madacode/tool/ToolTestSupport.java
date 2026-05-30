package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.tool.validation.ToolInputCoercion;

/**
 * Test-only convenience for invoking tools with raw JSON input.
 *
 * <p>Production code never goes through here — {@link madacode.core.engine.ToolExecutor}
 * does the equivalent coercion at runtime. Tests use this helper to avoid
 * having to construct each tool's typed input record by hand for assertions
 * that don't really care about the schema layer.
 */
public final class ToolTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolTestSupport() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ToolResult invoke(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        Object typed = ToolInputCoercion.coerceUnchecked(tool, input, MAPPER);
        return ((Tool) tool).execute(typed, context);
    }
}
