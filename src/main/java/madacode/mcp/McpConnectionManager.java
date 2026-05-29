package madacode.mcp;

import madacode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the lifecycle of all configured MCP servers.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load config and create {@link McpServer} instances</li>
 *   <li>Concurrently start all non-disabled servers</li>
 *   <li>Bridge discovered tools into {@link ToolRegistry}</li>
 *   <li>Shutdown all servers on close</li>
 * </ul>
 */
public class McpConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    private final McpToolBridge toolBridge;
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpConnectionManager(ToolRegistry registry) {
        this.toolBridge = new McpToolBridge(registry);
    }

    /**
     * Loads {@code ~/.mada/mcp.json}, starts each non-disabled server in
     * parallel, discovers tools, and registers them.
     *
     * @return list of tool names successfully registered
     */
    public List<String> initialize() {
        McpConfig config = McpConfigLoader.load();
        if (config.servers().isEmpty()) return List.of();

        // Create McpServer instances
        config.servers().forEach((name, serverConfig) -> {
            McpServer server = new McpServer(name, serverConfig);
            if (serverConfig.disabled()) {
                server.transitionTo(McpServer.Status.DISABLED);
            }
            servers.put(name, server);
        });

        List<McpServer> targets = servers.values().stream()
                .filter(s -> !s.config().disabled())
                .toList();
        if (targets.isEmpty()) return List.of();

        // Concurrent startup
        int threads = Math.min(targets.size(), 8);
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "mcp-startup-" + threadId.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        List<String> allRegistered = new ArrayList<>();
        try {
            List<CompletableFuture<List<String>>> futures = targets.stream()
                    .map(server -> CompletableFuture.supplyAsync(
                            () -> startOne(server), executor))
                    .toList();
            CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<List<String>> f : futures) {
                try {
                    allRegistered.addAll(f.get());
                } catch (Exception ignored) {
                    // individual errors already logged in startOne
                }
            }
        } finally {
            executor.shutdown();
        }

        return List.copyOf(allRegistered);
    }

    private List<String> startOne(McpServer server) {
        String name = server.name();
        try {
            server.transitionTo(McpServer.Status.STARTING);
            server.start();
            server.discoverTools();
            server.transitionTo(McpServer.Status.READY);

            List<String> names = toolBridge.register(server);
            log.info("MCP server '{}' ready — {} tool(s)", name, names.size());
            return names;
        } catch (Exception e) {
            server.transitionTo(McpServer.Status.ERROR);
            server.setErrorMessage(e.getMessage());
            log.warn("MCP server '{}' failed: {}", name, e.getMessage());
            server.close();
            return List.of();
        }
    }

    /** Access a server by name (for status inspection). */
    public McpServer server(String name) {
        return servers.get(name);
    }

    public java.util.Collection<McpServer> allServers() {
        return List.copyOf(servers.values());
    }

    // VisibleForTesting — used by tests in the same package to inject test servers without going through real stdio transport.
    public void registerForTesting(McpServer server) {
        servers.put(server.name(), server);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (McpServer server : servers.values()) {
            try {
                toolBridge.unregister(server);
                server.close();
            } catch (Exception e) {
                log.debug("Error closing MCP server '{}': {}",
                        server.name(), e.getMessage());
            }
        }
        servers.clear();
    }
}
