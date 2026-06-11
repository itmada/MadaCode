package madacode.bootstrap;

import madacode.events.AppEventPublisher;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.mcp.McpConnectionManager;
import madacode.mcp.McpServer;
import madacode.tool.McpListResourcesTool;
import madacode.tool.McpReadResourceTool;
import madacode.tool.blob.FilesystemBlobStore;
import madacode.tool.blob.McpBlobStore;

final class McpToolModule implements ToolModule {

    private final AppEventPublisher publisher;

    McpToolModule(AppEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void install(ToolContext context) {
        McpConnectionManager mcpManager = context.resources().own(
                new McpConnectionManager(
                        context.registry(),
                        context.environment().paths().globalMcpConfigFile()));
        mcpManager.initialize();
        long total = mcpManager.allServers().stream()
                .filter(s -> s.status() != McpServer.Status.DISABLED)
                .count();
        long ready = mcpManager.allServers().stream()
                .filter(s -> s.status() == McpServer.Status.READY)
                .count();
        if (total > 0) {
            publisher.publish(UserVisibleEvent.info(
                    EventContext.bootstrap("McpConnectionManager"),
                    "MCP servers loaded: " + ready + "/" + total + " ready"));
        }
        context.mcpManager(mcpManager);
        McpBlobStore blobStore = new FilesystemBlobStore(
                context.environment().paths().globalBlobsDir());
        context.register(new McpListResourcesTool(mcpManager));
        context.register(new McpReadResourceTool(mcpManager, blobStore));
    }
}
