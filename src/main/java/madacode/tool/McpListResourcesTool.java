package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.mcp.McpConnectionManager;
import madacode.mcp.McpException;
import madacode.mcp.McpResourceClient;
import madacode.mcp.McpResourceDescriptor;
import madacode.mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class McpListResourcesTool implements Tool<McpListResourcesTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(McpListResourcesTool.class);

    public record Input(String server) {}

    private final McpConnectionManager manager;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpListResourcesTool(McpConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() { return "list_mcp_resources"; }

    @Override
    public String description() {
        return "Lists available resources from configured MCP servers. Pass `server` to query a specific server, or omit to list across all connected servers.";
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
        properties.set("server", ToolSchemas.stringProperty(mapper, "Name of the MCP server to query. Omit to list all."));
        return ToolSchemas.objectSchema(mapper, properties);
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        try {
            if (input.server() != null && !input.server().isBlank()) {
                return executeSingle(input.server());
            }
            return executeFanOut();
        } catch (Exception e) {
            return new ToolResult(name(), false, "Unexpected error: " + e.getMessage());
        }
    }

    private ToolResult executeSingle(String serverName) {
        McpServer server = manager.server(serverName);
        if (server == null || server.status() != McpServer.Status.READY) {
            String available = availableServerNames();
            return new ToolResult(name(), false,
                    "Server \"" + serverName + "\" not found or not ready. Available servers: " + available);
        }
        try {
            List<McpResourceDescriptor> resources = new McpResourceClient(server.client()).listResources()
                    .stream()
                    .map(r -> new McpResourceDescriptor(server.name(), r.uri(), r.name(), r.mimeType(), r.description()))
                    .toList();
            ObjectNode out = mapper.createObjectNode();
            out.set("resources", toJsonArray(resources));
            return new ToolResult(name(), true, mapper.writeValueAsString(out));
        } catch (Exception e) {
            return new ToolResult(name(), false, "MCP error: " + e.getMessage());
        }
    }

    private ToolResult executeFanOut() {
        Collection<McpServer> all = manager.allServers();
        List<McpResourceDescriptor> resources = new ArrayList<>();
        List<ObjectNode> errors = new ArrayList<>();

        for (McpServer server : all) {
            if (server.status() != McpServer.Status.READY) continue;
            try {
                List<McpResourceDescriptor> found = new McpResourceClient(server.client()).listResources()
                        .stream()
                        .map(r -> new McpResourceDescriptor(server.name(), r.uri(), r.name(), r.mimeType(), r.description()))
                        .toList();
                resources.addAll(found);
            } catch (McpException e) {
                log.warn("Failed to list resources from MCP server '{}': {}", server.name(), e.getMessage());
                ObjectNode err = mapper.createObjectNode();
                err.put("server", server.name());
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }

        resources.sort(Comparator.comparing(McpResourceDescriptor::server, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(McpResourceDescriptor::uri, Comparator.nullsLast(Comparator.naturalOrder())));

        try {
            ObjectNode out = mapper.createObjectNode();
            out.set("resources", toJsonArray(resources));
            ArrayNode errArray = mapper.createArrayNode();
            errors.forEach(errArray::add);
            out.set("errors", errArray);
            return new ToolResult(name(), true, mapper.writeValueAsString(out));
        } catch (Exception e) {
            return new ToolResult(name(), false, "Serialization error: " + e.getMessage());
        }
    }

    private ArrayNode toJsonArray(List<McpResourceDescriptor> resources) {
        ArrayNode arr = mapper.createArrayNode();
        for (McpResourceDescriptor r : resources) {
            ObjectNode node = mapper.createObjectNode();
            if (r.server() != null) node.put("server", r.server());
            node.put("uri", r.uri());
            if (r.name() != null) node.put("name", r.name());
            if (r.mimeType() != null) node.put("mimeType", r.mimeType());
            if (r.description() != null) node.put("description", r.description());
            arr.add(node);
        }
        return arr;
    }

    private String availableServerNames() {
        return manager.allServers().stream()
                .map(McpServer::name)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
