package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/** Docker sidecar that is the only egress path from Phase-2 eval attempt containers. */
public final class DockerEgressProxyMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FORWARDED_HEADER_EXCLUSIONS = Set.of(
            "authorization",
            "connection",
            "content-length",
            "expect",
            "host",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<Integer, Route> routes;
    private final Path eventLog;

    private DockerEgressProxyMain(Map<Integer, Route> routes, Path eventLog) {
        this.routes = Map.copyOf(routes);
        this.eventLog = eventLog;
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        DockerEgressProxyConfigJson config =
                MAPPER.readValue(parsed.config().toFile(), DockerEgressProxyConfigJson.class);
        Path eventLog = parsed.eventLog();
        Path parent = eventLog.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        DockerEgressProxyMain proxy = new DockerEgressProxyMain(routes(config), eventLog);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.port()), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "OK\n"));
        server.createContext("/", proxy::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("mada egress proxy listening on " + config.port());
    }

    private static Map<Integer, Route> routes(DockerEgressProxyConfigJson config) {
        Map<Integer, Route> routes = new HashMap<>();
        for (DockerEgressProxyConfigJson.ProviderRouteJson provider : config.providers()) {
            String token = firstNonBlank(
                    System.getenv(provider.authTokenEnv()),
                    System.getProperty(provider.authTokenEnv()));
            if (token == null) {
                throw new IllegalStateException("provider token env is not set: " + provider.authTokenEnv());
            }
            routes.put(provider.index(), new Route(provider.name(), URI.create(provider.baseUrl()), token));
        }
        return routes;
    }

    private void handle(HttpExchange exchange) throws IOException {
        RequestTarget target = requestTarget(exchange.getRequestURI());
        if (target == null) {
            record("", true, "kind=blocked;reason=unknown-route;path=" + exchange.getRequestURI());
            respond(exchange, 403, "egress proxy blocked unknown route\n");
            return;
        }
        Route route = routes.get(target.providerIndex());
        if (route == null) {
            record("", true, "kind=blocked;reason=unknown-provider;index=" + target.providerIndex());
            respond(exchange, 403, "egress proxy blocked unknown provider\n");
            return;
        }
        URI upstream = upstreamUri(route.baseUrl(), target.suffix(), exchange.getRequestURI().getRawQuery());
        try {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder(upstream)
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!FORWARDED_HEADER_EXCLUSIONS.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> builder.header(name, value));
                }
            });
            builder.header("Authorization", "Bearer " + route.authToken());
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            record(upstream.getHost(), false, "kind=provider-api;provider=" + route.name()
                    + ";status=" + response.statusCode());
            response.headers().map().forEach((name, values) -> {
                if (!FORWARDED_HEADER_EXCLUSIONS.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> exchange.getResponseHeaders().add(name, value));
                }
            });
            exchange.sendResponseHeaders(response.statusCode(), response.body().length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(upstream.getHost(), false, "kind=provider-api;provider=" + route.name()
                    + ";error=interrupted");
            respond(exchange, 502, "egress proxy interrupted\n");
        } catch (RuntimeException | IOException e) {
            record(upstream.getHost(), false, "kind=provider-api;provider=" + route.name()
                    + ";error=" + sanitize(e.getMessage()));
            respond(exchange, 502, "egress proxy upstream failure\n");
        }
    }

    private static RequestTarget requestTarget(URI requestUri) {
        String path = requestUri.getRawPath();
        if (path == null || !path.startsWith("/provider/")) {
            return null;
        }
        String rest = path.substring("/provider/".length());
        int slash = rest.indexOf('/');
        String indexText = slash < 0 ? rest : rest.substring(0, slash);
        String suffix = slash < 0 ? "" : rest.substring(slash);
        try {
            return new RequestTarget(Integer.parseInt(indexText), suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static URI upstreamUri(URI baseUrl, String suffix, String rawQuery) {
        String base = baseUrl.toString();
        if (base.endsWith("/") && suffix.startsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.endsWith("/") && !suffix.isBlank() && !suffix.startsWith("/")) {
            base += "/";
        }
        String uri = base + suffix;
        if (rawQuery != null && !rawQuery.isBlank()) {
            uri += "?" + rawQuery;
        }
        return URI.create(uri);
    }

    private synchronized void record(String destination, boolean blocked, String detail) {
        try {
            String json = MAPPER.writeValueAsString(Map.of(
                    "time", Instant.now().toString(),
                    "destination", destination == null ? "" : destination,
                    "blocked", blocked,
                    "detail", detail == null ? "" : detail));
            Files.writeString(eventLog, json + "\n", StandardCharsets.UTF_8,
                    Files.exists(eventLog)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException ignored) {
            // The proxy must preserve request behavior even if logging fails.
        }
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record Route(String name, URI baseUrl, String authToken) {
    }

    private record RequestTarget(int providerIndex, String suffix) {
    }

    private record Args(Path config, Path eventLog) {
        static Args parse(String[] args) {
            Path config = null;
            Path eventLog = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config" -> config = Path.of(value(args, ++i, "--config"));
                    case "--event-log" -> eventLog = Path.of(value(args, ++i, "--event-log"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (config == null || eventLog == null) {
                throw new IllegalArgumentException("--config and --event-log are required");
            }
            return new Args(config, eventLog);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
