package madacode.mcp;

import madacode.mcp.transport.McpTransport;
import madacode.mcp.transport.StdioTransport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Models the lifecycle of a single MCP server connection.
 *
 * <p>Holds the server's config, transport, JSON-RPC client, discovered
 * tools, and runtime status. Created by {@link McpServerManager} and
 * managed through its start/stop/enable/disable lifecycle.
 */
public final class McpServer implements AutoCloseable {

    public enum Status { IDLE, STARTING, READY, DISABLED, ERROR }

    private final String name;
    private final McpConfig.McpServerConfig config;
    private McpTransport transport;
    private McpClient client;
    private volatile Status status = Status.IDLE;
    private volatile String errorMessage;
    private Instant startedAt;
    private final List<McpToolSchema> tools = Collections.synchronizedList(new ArrayList<>());
    private final List<String> registeredToolNames = Collections.synchronizedList(new ArrayList<>());

    McpServer(String name, McpConfig.McpServerConfig config) {
        this.name = name;
        this.config = config;
    }

    // ---- accessors ------------------------------------------------------

    public String name() { return name; }
    public McpConfig.McpServerConfig config() { return config; }
    public McpClient client() { return client; }
    public Status status() { return status; }
    public String errorMessage() { return errorMessage; }
    public List<McpToolSchema> tools() { return List.copyOf(tools); }
    public List<String> registeredToolNames() { return List.copyOf(registeredToolNames); }

    void setRegisteredToolNames(List<String> names) {
        registeredToolNames.clear();
        registeredToolNames.addAll(names);
    }

    void clearRegisteredToolNames() { registeredToolNames.clear(); }

    public List<String> logs() {
        return transport != null ? transport.stderrLines() : List.of();
    }
    public Duration uptime() {
        return startedAt != null ? Duration.between(startedAt, Instant.now()) : Duration.ZERO;
    }

    // ---- lifecycle (package-private, called by McpServerManager) --------

    void transitionTo(Status newStatus) {
        this.status = newStatus;
        if (newStatus == Status.READY) {
            this.startedAt = Instant.now();
        }
    }

    void setErrorMessage(String msg) { this.errorMessage = msg; }

    void start() throws McpException {
        this.transport = new StdioTransport(config);
        this.client = new McpClient(transport);
        client.start();
    }

    void discoverTools() throws McpException {
        if (client == null) throw new McpException("Server not started");
        tools.clear();
        tools.addAll(client.listTools());
    }

    void clearTools() { tools.clear(); }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        } else if (transport != null) {
            transport.close();
        }
    }
}
