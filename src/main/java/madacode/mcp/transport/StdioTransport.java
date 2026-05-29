package madacode.mcp.transport;

import madacode.mcp.McpConfig;
import madacode.mcp.McpException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP transport over a subprocess's stdin/stdout (stdio).
 *
 * <p>The stderr stream is drained in a daemon thread and buffered so
 * callers can inspect it via {@link #drainStderr()}.
 */
public final class StdioTransport implements McpTransport {

    private final List<String> command;
    private final Map<String, String> env;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean closed;

    public StdioTransport(McpConfig.McpServerConfig config) {
        this.command = new ArrayList<>();
        this.command.add(config.command());
        this.command.addAll(config.args());
        this.env = config.env().isEmpty() ? Map.of() : Map.copyOf(config.env());
    }

    @Override
    public void start() throws McpException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        if (!env.isEmpty()) {
            pb.environment().putAll(env);
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new McpException(
                    "Failed to start MCP server process: " + command.getFirst(), e);
        }

        writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));

        Thread stderrThread = new Thread(() -> {
            try (var err = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = err.readLine()) != null) {
                    stderrLines.add(line);
                }
            } catch (IOException ignored) {
            }
        }, "mcp-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    @Override
    public void send(String jsonLine) throws IOException {
        if (writer == null) throw new IOException("Transport not started");
        synchronized (writer) {
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
        }
    }

    @Override
    public String readLine() throws IOException {
        if (reader == null) throw new IOException("Transport not started");
        return reader.readLine();
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive() && !closed;
    }

    @Override
    public List<String> drainStderr() {
        synchronized (stderrLines) {
            List<String> copy = List.copyOf(stderrLines);
            stderrLines.clear();
            return copy;
        }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLines) {
            return List.copyOf(stderrLines);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
