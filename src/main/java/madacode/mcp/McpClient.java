package madacode.mcp;

import madacode.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP JSON-RPC client over a {@link McpTransport}.
 *
 * <p>The client owns the protocol layer (initialize handshake, request/response
 * routing via {@link CompletableFuture}) but delegates all I/O to the transport.
 */
public class McpClient implements AutoCloseable {

    private static final int INITIALIZE_TIMEOUT_SECONDS = 10;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private Thread readerThread;
    private volatile boolean closed;

    public McpClient(McpTransport transport) {
        this.transport = transport;
    }

    // ---- lifecycle ------------------------------------------------------

    /** Start transport and perform MCP initialize handshake. */
    public void start() throws McpException {
        transport.start();
        readerThread = startReaderThread();
        performInitialize();
    }

    private Thread startReaderThread() {
        Thread t = new Thread(() -> {
            try {
                String line;
                while (!closed && (line = transport.readLine()) != null) {
                    dispatchLine(line);
                }
            } catch (IOException e) {
                if (!closed) {
                    McpException disconnected = new McpException(
                            "MCP server disconnected: " + e.getMessage());
                    failAllPending(disconnected);
                }
            } finally {
                failAllPending(new McpException("MCP server disconnected"));
            }
        }, "mcp-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void failAllPending(McpException error) {
        for (var future : pending.values()) {
            future.completeExceptionally(error);
        }
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        transport.close();
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    public boolean isAlive() {
        return transport.isAlive() && !closed;
    }

    // ---- MCP protocol ---------------------------------------------------

    private void performInitialize() throws McpException {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "mada-agent");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);

        JsonNode result = sendRequest("initialize", params,
                INITIALIZE_TIMEOUT_SECONDS);
        if (!result.has("capabilities")) {
            throw new McpException(
                    "Invalid initialize response: missing capabilities");
        }
        sendNotification("notifications/initialized",
                mapper.createObjectNode());
    }

    public List<McpToolSchema> listTools() throws McpException {
        JsonNode result = sendRequest("tools/list",
                mapper.createObjectNode(), REQUEST_TIMEOUT_SECONDS);
        JsonNode toolsNode = result.path("tools");
        if (!toolsNode.isArray()) return List.of();

        List<McpToolSchema> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            String name = tool.path("name").asText("");
            String desc = tool.path("description").asText("");
            JsonNode inputSchema = tool.has("inputSchema")
                    ? tool.get("inputSchema")
                    : mapper.createObjectNode();
            if (!name.isBlank()) {
                tools.add(new McpToolSchema(name, desc, inputSchema));
            }
        }
        return List.copyOf(tools);
    }

    public String callTool(String toolName, JsonNode arguments)
            throws McpException {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments",
                arguments != null ? arguments : mapper.createObjectNode());

        JsonNode result = sendRequest("tools/call", params,
                REQUEST_TIMEOUT_SECONDS);

        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            return result.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                sb.append(item.path("text").asText());
            }
        }
        return sb.toString();
    }

    // ---- JSON-RPC -------------------------------------------------------

    JsonNode sendRequest(String method, JsonNode params, int timeoutSeconds)
            throws McpException {
        int id = nextId.getAndIncrement();
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            transport.send(mapper.writeValueAsString(request));
        } catch (IOException e) {
            pending.remove(id);
            throw new McpException("Failed to send request: " + e.getMessage(), e);
        }

        try {
            return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).get();
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new McpException(
                        "Request timed out after " + timeoutSeconds + "s: " + method);
            }
            if (cause instanceof McpException mce) throw mce;
            throw new McpException(
                    "Request failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Request interrupted: " + method);
        }
    }

    void sendNotification(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        try {
            transport.send(mapper.writeValueAsString(notification));
        } catch (IOException e) {
            // notifications are best-effort
        }
    }

    private void dispatchLine(String line) {
        if (line.isBlank()) return;
        try {
            JsonNode msg = mapper.readTree(line);
            JsonNode idNode = msg.get("id");
            if (idNode == null || idNode.isNull()) return; // notification
            int id = idNode.asInt();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) return;
            if (msg.has("error")) {
                JsonNode err = msg.get("error");
                String message = err.path("message").asText("unknown error");
                future.completeExceptionally(
                        new McpException("Server error: " + message));
            } else {
                future.complete(msg.path("result"));
            }
        } catch (Exception e) {
            // malformed line — ignore
        }
    }
}
