package madacode.mcp;

import madacode.mcp.transport.StdioTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResourceClientTest {

    private McpClient client;
    private McpResourceClient resourceClient;

    @BeforeEach
    void setUp() throws Exception {
        client = buildTestClient();
        client.start();
        resourceClient = new McpResourceClient(client);
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    void listResourcesReturnsThreeItems() throws Exception {
        List<McpResourceDescriptor> resources = resourceClient.listResources();
        assertEquals(3, resources.size());
    }

    @Test
    void listResourcesFieldsAligned() throws Exception {
        List<McpResourceDescriptor> resources = resourceClient.listResources();
        McpResourceDescriptor hello = resources.stream()
                .filter(r -> "test://text/hello".equals(r.uri()))
                .findFirst().orElseThrow();
        assertEquals("hello", hello.name());
        assertEquals("text/plain", hello.mimeType());
        assertEquals("A friendly greeting", hello.description());
    }

    @Test
    void listResourcesNullableFieldsOk() throws Exception {
        List<McpResourceDescriptor> resources = resourceClient.listResources();
        McpResourceDescriptor doc = resources.stream()
                .filter(r -> "test://text/markdown".equals(r.uri()))
                .findFirst().orElseThrow();
        assertEquals("doc", doc.name());
        assertEquals("text/markdown", doc.mimeType());
        // description is absent in server response — should be null
    }

    @Test
    void readTextResourceHasText() throws Exception {
        McpResourceContent content = resourceClient.readResource("test://text/hello");
        assertEquals(1, content.items().size());
        McpResourceContent.Item item = content.items().getFirst();
        assertTrue(item.hasText());
        assertEquals("hello world", item.text());
    }

    @Test
    void readBinaryResourceHasBlob() throws Exception {
        McpResourceContent content = resourceClient.readResource("test://binary/png");
        assertEquals(1, content.items().size());
        McpResourceContent.Item item = content.items().getFirst();
        assertTrue(item.hasBlob());
        assertNotNull(item.blobBase64());
    }

    @Test
    void readNonExistentUriThrowsMcpException() {
        assertThrows(McpException.class, () ->
                resourceClient.readResource("test://does-not-exist"));
    }

    // ---- helpers ----

    private static McpClient buildTestClient() throws Exception {
        URL url = McpResourceClientTest.class.getClassLoader()
                .getResource("mcp-resources-test-server.js");
        assertNotNull(url, "mcp-resources-test-server.js must be on the classpath");
        String script = Paths.get(url.toURI()).toAbsolutePath().toString();
        McpConfig.McpServerConfig cfg = new McpConfig.McpServerConfig("node", List.of(script), Map.of());
        return new McpClient(new StdioTransport(cfg));
    }
}
