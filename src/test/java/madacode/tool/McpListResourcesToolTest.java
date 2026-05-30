package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.mcp.McpClient;
import madacode.mcp.McpConfig;
import madacode.mcp.McpConnectionManager;
import madacode.mcp.McpServerTestFactory;
import madacode.mcp.McpServer;
import madacode.mcp.transport.StdioTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpListResourcesToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpClient client;
    private McpClient client2;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (client2 != null) client2.close();
    }

    @Test
    void unknownServerReturnsFail(@TempDir Path dir) {
        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        McpListResourcesTool tool = new McpListResourcesTool(manager);
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "ghost");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertFalse(result.success());
        assertTrue(result.output().contains("not found") || result.output().contains("not ready"),
                result.output());
    }

    @Test
    void noServersReturnsEmptyLists(@TempDir Path dir) throws Exception {
        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        McpListResourcesTool tool = new McpListResourcesTool(manager);
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ToolResult result = ToolTestSupport.invoke(tool, mapper.createObjectNode(), ctx);

        assertTrue(result.success());
        assertTrue(result.output().contains("\"resources\":[]"), result.output());
        assertTrue(result.output().contains("\"errors\":[]"), result.output());
    }

    @Test
    void happyPathWithTestServer(@TempDir Path dir) throws Exception {
        client = buildTestClient();
        client.start();
        McpServer server = McpServerTestFactory.readyServer("testserver", client);

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(server);

        McpListResourcesTool tool = new McpListResourcesTool(manager);
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "testserver");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"resources\""), result.output());
        // 3 resources from test server
        long count = result.output().chars().filter(c -> c == '{').count() - 1; // subtract outer object
        assertTrue(count >= 3, "Expected at least 3 resource entries, output: " + result.output());
        assertTrue(result.output().contains("testserver"), result.output());
    }

    @Test
    void fanOutSwallowsFailedServer(@TempDir Path dir) throws Exception {
        // good server
        client = buildTestClient();
        client.start();
        McpServer goodServer = McpServerTestFactory.readyServer("good", client);

        // bad server — close the client so listResources will fail
        client2 = buildTestClient();
        client2.start();
        McpServer badServer = McpServerTestFactory.readyServer("bad", client2);
        client2.close(); // force failure

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(goodServer);
        manager.registerForTesting(badServer);

        McpListResourcesTool tool = new McpListResourcesTool(manager);
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ToolResult result = ToolTestSupport.invoke(tool, mapper.createObjectNode(), ctx);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"resources\""), result.output());
        assertTrue(result.output().contains("\"errors\""), result.output());
        // errors should contain the bad server
        assertTrue(result.output().contains("bad"), result.output());
    }

    // ---- helpers ----

    private static McpClient buildTestClient() throws Exception {
        URL url = McpListResourcesToolTest.class.getClassLoader()
                .getResource("mcp-resources-test-server.js");
        String script = Paths.get(url.toURI()).toAbsolutePath().toString();
        McpConfig.McpServerConfig cfg = new McpConfig.McpServerConfig("node", List.of(script), Map.of());
        return new McpClient(new StdioTransport(cfg));
    }
}
