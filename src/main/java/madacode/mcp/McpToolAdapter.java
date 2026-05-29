package madacode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

/**
 * Wraps an MCP server-side tool. Schema is dynamic per server, so this
 * adapter sticks with raw {@link ObjectNode} as its input type — the model's
 * tool_use input is forwarded verbatim to the MCP server. Other tools use
 * strongly-typed records, but MCP's dynamic schema means we'd buy nothing
 * by adding one here.
 */
public class McpToolAdapter implements Tool<ObjectNode> {

    private final McpClient client;
    private final McpToolSchema schema;
    private final String adapterName;
    private final ObjectNode cachedInputSchema;

    public McpToolAdapter(McpClient client, McpToolSchema schema, String serverName) {
        this.client = client;
        this.schema = schema;
        this.adapterName = "mcp__" + serverName + "__" + schema.name();
        ObjectMapper mapper = new ObjectMapper();
        if (schema.inputSchema() instanceof ObjectNode on) {
            this.cachedInputSchema = on.deepCopy();
        } else {
            this.cachedInputSchema = mapper.createObjectNode();
        }
    }

    @Override
    public String name() {
        return adapterName;
    }

    @Override
    public String description() {
        return schema.description();
    }

    @Override
    public Class<ObjectNode> inputType() {
        return ObjectNode.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        return cachedInputSchema.deepCopy();
    }

    @Override
    public ToolResult execute(ObjectNode input, ToolUseContext context) {
        if (!client.isAlive()) {
            return new ToolResult(adapterName, false, "MCP server is not running");
        }
        try {
            String result = client.callTool(schema.name(), input);
            return new ToolResult(adapterName, true, result);
        } catch (McpException e) {
            return new ToolResult(adapterName, false, "MCP tool error: " + e.getMessage());
        }
    }
}
