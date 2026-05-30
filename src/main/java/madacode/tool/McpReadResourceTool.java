package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.mcp.McpConnectionManager;
import madacode.mcp.McpException;
import madacode.mcp.McpResourceClient;
import madacode.mcp.McpResourceContent;
import madacode.mcp.McpServer;
import madacode.tool.blob.McpBlobStore;

import java.util.Base64;
import java.util.stream.Collectors;

public class McpReadResourceTool implements Tool<McpReadResourceTool.Input> {

    public record Input(String server, String uri) {}

    private final McpConnectionManager manager;
    private final McpBlobStore blobStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpReadResourceTool(McpConnectionManager manager, McpBlobStore blobStore) {
        this.manager = manager;
        this.blobStore = blobStore;
    }

    @Override
    public String name() { return "read_mcp_resource"; }

    @Override
    public String description() {
        return "Reads a specific MCP resource by server name and URI. Text resources are returned inline; binary blobs are decoded and persisted to ~/.mada/blobs and the path is returned.";
    }

    @Override
    public Class<Input> inputType() { return Input.class; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("server", ToolSchemas.stringProperty(mapper, "Name of the MCP server."));
        properties.set("uri", ToolSchemas.stringProperty(mapper, "URI of the resource to read."));
        return ToolSchemas.objectSchema(mapper, properties, "server", "uri");
    }

    @Override
    public String approvalSignature(ObjectNode input) {
        return input.path("server").asText("") + "\n" + input.path("uri").asText("");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        if (input.server() == null || input.server().isBlank()) {
            return new ToolResult(name(), false, "Missing required field: server");
        }
        if (input.uri() == null || input.uri().isBlank()) {
            return new ToolResult(name(), false, "Missing required field: uri");
        }

        McpServer server = manager.server(input.server());
        if (server == null) {
            String available = manager.allServers().stream()
                    .map(McpServer::name).sorted().collect(Collectors.joining(", "));
            return new ToolResult(name(), false,
                    "Server \"" + input.server() + "\" not found. Available servers: " + available);
        }
        if (server.status() != McpServer.Status.READY) {
            return new ToolResult(name(), false,
                    "Server \"" + input.server() + "\" is not ready (state: " + server.status() + ")");
        }

        try {
            McpResourceContent content = new McpResourceClient(server.client()).readResource(input.uri());
            ArrayNode items = mapper.createArrayNode();
            for (McpResourceContent.Item item : content.items()) {
                items.add(buildItemNode(item));
            }
            ObjectNode out = mapper.createObjectNode();
            out.set("contents", items);
            return new ToolResult(name(), true, mapper.writeValueAsString(out));
        } catch (McpException e) {
            return new ToolResult(name(), false, "MCP error: " + e.getMessage());
        } catch (Exception e) {
            return new ToolResult(name(), false, "Unexpected error: " + e.getMessage());
        }
    }

    private ObjectNode buildItemNode(McpResourceContent.Item item) {
        ObjectNode node = mapper.createObjectNode();
        node.put("uri", item.uri());
        if (item.mimeType() != null) node.put("mimeType", item.mimeType());

        if (item.hasText()) {
            node.put("text", item.text());
        } else if (item.hasBlob()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(item.blobBase64());
                McpBlobStore.Persisted p = blobStore.persist(bytes, item.mimeType());
                node.put("blobSavedTo", p.path().toString());
                node.put("sizeBytes", p.bytes());
                node.put("text", "[Binary content saved to " + p.path() + " — " + p.bytes() + " bytes, " + item.mimeType() + "]");
            } catch (Exception e) {
                node.put("text", "[Failed to decode binary content: " + e.getMessage() + "]");
            }
        }
        return node;
    }
}
