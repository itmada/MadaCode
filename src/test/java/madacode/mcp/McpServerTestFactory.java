package madacode.mcp;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public final class McpServerTestFactory {
    private McpServerTestFactory() {}

    public static McpServer readyServer(String name, McpClient client) throws Exception {
        McpConfig.McpServerConfig cfg = new McpConfig.McpServerConfig("dummy", List.of(), Map.of());
        McpServer server = new McpServer(name, cfg);
        Field clientField = McpServer.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(server, client);
        server.transitionTo(McpServer.Status.READY);
        return server;
    }
}
