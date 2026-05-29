package madacode.mcp;

import madacode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges MCP-discovered tools into the global {@link ToolRegistry}.
 *
 * <p>Keeps {@link McpServerManager} free of tool-registration details and
 * ensures consistent {@code mcp__} namespace prefixing.
 */
final class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final ToolRegistry registry;

    McpToolBridge(ToolRegistry registry) {
        this.registry = registry;
    }

    /** Register all tools from a server. Returns the registered names. */
    java.util.List<String> register(McpServer server) {
        var names = new java.util.ArrayList<String>();
        for (McpToolSchema schema : server.tools()) {
            McpToolAdapter adapter = new McpToolAdapter(server.client(), schema, server.name());
            registry.register(adapter);
            names.add(adapter.name());
            log.debug("Registered MCP tool: {}", adapter.name());
        }
        server.setRegisteredToolNames(names);
        return names;
    }

    /** Unregister all tools from a server. */
    void unregister(McpServer server) {
        for (String name : server.registeredToolNames()) {
            registry.remove(name);
        }
        server.clearRegisteredToolNames();
    }
}
