package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.mcp.McpClient;
import madacode.mcp.McpConfig;
import madacode.mcp.McpConnectionManager;
import madacode.mcp.McpServer;
import madacode.mcp.McpServerTestFactory;
import madacode.mcp.transport.StdioTransport;
import madacode.tool.blob.FilesystemBlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpReadResourceToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpClient client;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    void serverNotFoundReturnsFail(@TempDir Path dir) {
        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        McpReadResourceTool tool = new McpReadResourceTool(manager, new FilesystemBlobStore(dir.resolve("blobs")));
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "ghost");
        input.put("uri", "test://text/hello");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertFalse(result.success());
        assertTrue(result.output().contains("not found"), result.output());
    }

    @Test
    void serverNotReadyReturnsFail(@TempDir Path dir) throws Exception {
        // Build a client but don't start it — server will be in IDLE state
        McpConfig.McpServerConfig cfg = new McpConfig.McpServerConfig("node", List.of(), Map.of());
        // Use a dummy client that is closed immediately so the server stays non-READY
        client = buildTestClient();
        client.start();
        McpServer server = McpServerTestFactory.readyServer("idle", client);
        // Force back to a non-READY state by transitioning — but transitionTo is package-private.
        // Instead, just don't register as READY: create a fresh server via reflection with IDLE status.
        // Simplest: use a second manager with a server that was never transitioned to READY.
        // We can achieve this by creating the McpServer via McpServerTestFactory but then
        // using a separate manager where we inject a server that has ERROR status.
        // Actually the easiest path: create a McpServer via McpServerTestFactory with a closed client,
        // then manually set status to ERROR via transitionTo (package-private, but we're in madacode.tool).
        // We can't call transitionTo from here. Use reflection.
        java.lang.reflect.Field statusField = McpServer.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(server, McpServer.Status.ERROR);

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(server);

        McpReadResourceTool tool = new McpReadResourceTool(manager, new FilesystemBlobStore(dir.resolve("blobs")));
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "idle");
        input.put("uri", "test://text/hello");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertFalse(result.success());
        assertTrue(result.output().contains("not ready"), result.output());
    }

    @Test
    void readTextResource(@TempDir Path dir) throws Exception {
        client = buildTestClient();
        client.start();
        McpServer server = McpServerTestFactory.readyServer("ts", client);

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(server);

        McpReadResourceTool tool = new McpReadResourceTool(manager, new FilesystemBlobStore(dir.resolve("blobs")));
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "ts");
        input.put("uri", "test://text/hello");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("hello world"), result.output());
        assertFalse(result.output().contains("blobSavedTo"), result.output());
    }

    @Test
    void readMarkdownResource(@TempDir Path dir) throws Exception {
        client = buildTestClient();
        client.start();
        McpServer server = McpServerTestFactory.readyServer("ts", client);

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(server);

        McpReadResourceTool tool = new McpReadResourceTool(manager, new FilesystemBlobStore(dir.resolve("blobs")));
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "ts");
        input.put("uri", "test://text/markdown");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("# Hello"), result.output());
        assertTrue(result.output().contains("markdown"), result.output());
    }

    @Test
    void readBinaryResource(@TempDir Path dir) throws Exception {
        client = buildTestClient();
        client.start();
        McpServer server = McpServerTestFactory.readyServer("ts", client);

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(server);

        Path blobsDir = dir.resolve("blobs");
        McpReadResourceTool tool = new McpReadResourceTool(manager, new FilesystemBlobStore(blobsDir));
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "ts");
        input.put("uri", "test://binary/png");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("blobSavedTo"), result.output());

        // extract path from output and verify file exists with .png extension
        String output = result.output();
        int pathStart = output.indexOf("blobSavedTo") + "blobSavedTo\":\"".length();
        int pathEnd = output.indexOf("\"", pathStart);
        String savedPath = output.substring(pathStart, pathEnd);
        Path savedFile = Path.of(savedPath);
        assertTrue(Files.exists(savedFile), "Blob file should exist at: " + savedPath);
        assertTrue(savedPath.endsWith(".png"), "Should have .png extension: " + savedPath);

        // verify bytes match server-side content
        byte[] expected = "PNG_FAKE_BYTES_123".getBytes();
        byte[] actual = Files.readAllBytes(savedFile);
        assertTrue(actual.length == expected.length, "Byte count mismatch");
    }

    @Test
    void readNonExistentUriReturnsFail(@TempDir Path dir) throws Exception {
        client = buildTestClient();
        client.start();
        McpServer server = McpServerTestFactory.readyServer("ts", client);

        McpConnectionManager manager = new McpConnectionManager(new ToolRegistry());
        manager.registerForTesting(server);

        McpReadResourceTool tool = new McpReadResourceTool(manager, new FilesystemBlobStore(dir.resolve("blobs")));
        ToolUseContext ctx = new ToolUseContext(dir, new ConversationSession(dir));

        ObjectNode input = mapper.createObjectNode();
        input.put("server", "ts");
        input.put("uri", "test://does-not-exist");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx);

        assertFalse(result.success());
        assertTrue(result.output().contains("MCP error"), result.output());
    }

    // ---- helpers ----

    private static McpClient buildTestClient() throws Exception {
        URL url = McpReadResourceToolTest.class.getClassLoader()
                .getResource("mcp-resources-test-server.js");
        String script = Paths.get(url.toURI()).toAbsolutePath().toString();
        McpConfig.McpServerConfig cfg = new McpConfig.McpServerConfig("node", List.of(script), Map.of());
        return new McpClient(new StdioTransport(cfg));
    }
}
