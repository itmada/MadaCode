package madacode.mcp;

import java.util.List;
import java.util.Map;

public record McpConfig(Map<String, McpServerConfig> servers) {

    public McpConfig {
        servers = servers == null ? Map.of() : Map.copyOf(servers);
    }

    public record McpServerConfig(
            String command,
            List<String> args,
            Map<String, String> env,
            boolean disabled
    ) {
        public McpServerConfig(String command, List<String> args, Map<String, String> env) {
            this(command, args, env, false);
        }

        public McpServerConfig {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
        }
    }
}
