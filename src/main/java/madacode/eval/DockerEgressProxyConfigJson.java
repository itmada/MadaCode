package madacode.eval;

import java.util.List;

/** File-protocol DTO for the docker egress proxy sidecar. */
record DockerEgressProxyConfigJson(
        String schemaVersion,
        int port,
        List<ProviderRouteJson> providers) {

    static final String SCHEMA_VERSION = "1";

    DockerEgressProxyConfigJson {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported egress proxy config schemaVersion " + schemaVersion);
        }
        port = port <= 0 ? 8080 : port;
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    record ProviderRouteJson(
            int index,
            String name,
            String baseUrl,
            String authTokenEnv) {
        ProviderRouteJson {
            if (index < 0) {
                throw new IllegalArgumentException("provider index must be non-negative");
            }
            name = name == null ? "" : name;
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("provider baseUrl is required");
            }
            if (authTokenEnv == null || authTokenEnv.isBlank()) {
                throw new IllegalArgumentException("provider authTokenEnv is required");
            }
        }
    }
}
