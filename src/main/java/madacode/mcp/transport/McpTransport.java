package madacode.mcp.transport;

import madacode.mcp.McpException;

import java.io.IOException;
import java.util.List;

/**
 * Transport abstraction for MCP client connections.
 *
 * <p>Modeled after {@code @modelcontextprotocol/sdk}'s {@code Transport}
 * interface. Separates the wire protocol (JSON-RPC over lines) from the
 * actual I/O mechanism (stdio subprocess, HTTP streaming, etc.).
 */
public interface McpTransport extends AutoCloseable {

    /** Start the underlying connection. */
    void start() throws McpException;

    /** Send a single JSON line (no trailing newline; the transport adds it). */
    void send(String jsonLine) throws IOException;

    /** Blocking read of one JSON line (without trailing newline). */
    String readLine() throws IOException;

    /** Whether the underlying connection is still alive. */
    boolean isAlive();

    /** Stderr lines accumulated since last drain. Destructive — clears after read. */
    default List<String> drainStderr() { return List.of(); }

    /** Non-destructive snapshot of stderr lines accumulated so far. */
    default List<String> stderrLines() { return List.of(); }

    @Override
    void close();
}
