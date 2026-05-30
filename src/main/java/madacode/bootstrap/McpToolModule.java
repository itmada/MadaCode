package madacode.bootstrap;

import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.mcp.McpConnectionManager;
import madacode.mcp.McpServer;
import madacode.tool.MadaPaths;
import madacode.tool.McpListResourcesTool;
import madacode.tool.McpReadResourceTool;
import madacode.tool.blob.FilesystemBlobStore;
import madacode.tool.blob.McpBlobStore;

final class McpToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        McpConnectionManager mcpManager = context.resources().own(
                new McpConnectionManager(context.registry()));
        mcpManager.initialize();
        long total = mcpManager.allServers().stream()
                .filter(s -> s.status() != McpServer.Status.DISABLED)
                .count();
        long ready = mcpManager.allServers().stream()
                .filter(s -> s.status() == McpServer.Status.READY)
                .count();
        if (total > 0) {
            AppEvents.publisher().publish(UserVisibleEvent.info(
                    EventContext.bootstrap("McpConnectionManager"),
                    "MCP servers loaded: " + ready + "/" + total + " ready"));
        }
        context.mcpManager(mcpManager);
        McpBlobStore blobStore = new FilesystemBlobStore(MadaPaths.blobsDir());
        context.register(new McpListResourcesTool(mcpManager));
        context.register(new McpReadResourceTool(mcpManager, blobStore));
    }
}
