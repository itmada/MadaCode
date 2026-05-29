package madacode.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McpConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final Path CONFIG_PATH =
            Path.of(System.getProperty("user.home"), ".mada", "mcp.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private McpConfigLoader() {
    }

    public static McpConfig load() {
        return load(CONFIG_PATH);
    }

    static McpConfig load(Path path) {
        if (!Files.exists(path)) {
            return new McpConfig(java.util.Map.of());
        }
        try {
            McpConfigFile file = MAPPER.readValue(path.toFile(), McpConfigFile.class);
            if (file == null || file.servers() == null) {
                return new McpConfig(java.util.Map.of());
            }
            java.util.Map<String, McpConfig.McpServerConfig> servers = new java.util.LinkedHashMap<>();
            for (var entry : file.servers().entrySet()) {
                McpConfigFile.ServerEntry s = entry.getValue();
                servers.put(entry.getKey(), new McpConfig.McpServerConfig(
                        s.command(),
                        s.args(),
                        s.env(),
                        s.disabled()));
            }
            return new McpConfig(servers);
        } catch (IOException e) {
            log.warn("Failed to parse MCP config at {}: {}", path, e.getMessage());
            return new McpConfig(java.util.Map.of());
        }
    }

    // Jackson deserialization target — separate from the public API record
    private record McpConfigFile(
            java.util.Map<String, ServerEntry> servers
    ) {
        private record ServerEntry(
                String command,
                java.util.List<String> args,
                java.util.Map<String, String> env,
                boolean disabled
        ) {}
    }
}
