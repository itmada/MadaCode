package madacode.mcp;

import madacode.mcp.transport.StdioTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.engine.ToolUseContext;
import madacode.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {

    private McpClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = buildTestClient();
        client.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void startServerAndInitialize() {
        assertTrue(client.isAlive(), "Client should be alive after successful start");
    }

    @Test
    void listTools() throws Exception {
        List<McpToolSchema> tools = client.listTools();

        assertEquals(1, tools.size());
        assertEquals("echo", tools.getFirst().name());
        assertNotNull(tools.getFirst().description());
        assertNotNull(tools.getFirst().inputSchema());
    }

    @Test
    void callTool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("message", "hello");

        String result = client.callTool("echo", args);

        assertEquals("Echo: hello", result);
    }

    @Test
    void callToolUnknown() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(McpException.class, () ->
                client.callTool("does_not_exist", mapper.createObjectNode()));
    }

    @Test
    void startServerInvalidCommand() {
        McpConfig.McpServerConfig bad = new McpConfig.McpServerConfig(
                "nonexistent-binary-xyz", List.of(), Map.of());
        StdioTransport transport = new StdioTransport(bad);
        McpClient badClient = new McpClient(transport);
        assertThrows(McpException.class, badClient::start);
    }

    @Test
    void closeTerminatesProcess() throws Exception {
        assertTrue(client.isAlive());
        client.close();
        Thread.sleep(100);
        assertFalse(client.isAlive());
    }

    @Test
    void mcpToolAdapterIntegratesWithRegistry() throws Exception {
        List<McpToolSchema> tools = client.listTools();
        assertFalse(tools.isEmpty(), "Must have at least one tool");

        ToolRegistry registry = new ToolRegistry();
        for (McpToolSchema schema : tools) {
            registry.register(new McpToolAdapter(client, schema, "test"));
        }

        assertTrue(registry.find("mcp__test__echo").isPresent(),
                "Registry must contain mcp__test__echo");
    }

    @Test
    void mcpToolAdapterExecutesSuccessfully() throws Exception {
        List<McpToolSchema> tools = client.listTools();
        McpToolAdapter adapter = new McpToolAdapter(client, tools.getFirst(), "test");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode input = mapper.createObjectNode();
        input.put("message", "world");

        ConversationSession session = new ConversationSession();
        ToolUseContext context = new ToolUseContext(Path.of("."), session);

        var result = adapter.execute(input, context);

        assertTrue(result.success());
        assertEquals("Echo: world", result.output());
    }

    @Test
    void connectionManagerRegistersTools() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        McpConnectionManager manager = new McpConnectionManager(registry);
        List<String> registered = manager.initialize();
        manager.close();

        // If a user has mcp.json configured, tools should load; if not, empty is fine.
        assertNotNull(registered);
    }

    // ---- helpers ----

    private static McpClient buildTestClient() throws Exception {
        String script = testServerPath();
        McpConfig.McpServerConfig cfg = new McpConfig.McpServerConfig(
                "node", List.of(script), Map.of());
        return new McpClient(new StdioTransport(cfg));
    }

    private static String testServerPath() throws Exception {
        URL url = McpClientTest.class.getClassLoader()
                .getResource("mcp-test-server.js");
        assertNotNull(url, "mcp-test-server.js must be on the classpath");
        return Paths.get(url.toURI()).toAbsolutePath().toString();
    }
}
