package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import com.sun.net.httpserver.HttpServer;

import madacode.core.session.SessionListener;
import madacode.core.engine.ToolExecutor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebFetchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WebFetchTool tool;
    private ToolUseContext context;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new WebFetchTool(false); // disable localhost restriction for tests
        context = new ToolUseContext(tempDir, new ConversationSession(tempDir));
    }

    @Test
    void schemaRequiresOnlyUrl() {
        ObjectNode schema = tool.inputSchema(mapper);
        assertTrue(schema.path("required").isArray());
        assertEquals("url", schema.path("required").get(0).asText());
        assertEquals(1, schema.path("required").size());
    }

    @Test
    void descriptionStatesPromptIsNotInterpreted() {
        String description = tool.description();
        assertTrue(description.contains("does not interpret"));
    }

    @Test
    void fetchesHtmlPage() throws IOException {
        HttpServer server = startServer(200, "text/html",
                "<html><head><title>Test Page</title></head>"
                        + "<body><h1>Hello</h1><p>This is a test.</p>"
                        + "<script>alert('xss')</script></body></html>");
        try {
            ObjectNode input = fetchInput(serverUrl(server), "extract content");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success());
            String output = result.output();
            assertTrue(output.contains("Title: Test Page"));
            assertTrue(output.contains("Hello"));
            assertTrue(output.contains("This is a test"));
            assertFalse(output.contains("alert")); // script removed
            assertFalse(output.contains("xss"));
            assertTrue(output.contains("Status: 200"));
            assertTrue(output.contains("Note: prompt is not interpreted by web_fetch"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchesPlainText() throws IOException {
        HttpServer server = startServer(200, "text/plain", "hello world");
        try {
            ObjectNode input = fetchInput(serverUrl(server), "read it");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success());
            assertTrue(result.output().contains("hello world"));
            assertTrue(result.output().contains("Note: prompt is not interpreted by web_fetch"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void worksWithOnlyUrlAndNoPrompt() throws IOException {
        HttpServer server = startServer(200, "text/plain", "url-only");
        try {
            ObjectNode input = fetchInput(serverUrl(server));
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success());
            assertTrue(result.output().contains("url-only"));
            assertFalse(result.output().contains("prompt is not interpreted"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEmptyUrl() {
        ObjectNode input = fetchInput("", "prompt");
        ToolResult result = ToolTestSupport.invoke(tool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("URL is required"));
    }

    @Test
    void rejectsFileProtocol() {
        ObjectNode input = fetchInput("file:///etc/passwd", "prompt");
        ToolResult result = ToolTestSupport.invoke(tool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Unsupported protocol"));
    }

    @Test
    void rejectsLocalhost() {
        WebFetchTool restrictedTool = new WebFetchTool(true);
        ObjectNode input = fetchInput("http://localhost:8080/data", "prompt");
        ToolResult result = ToolTestSupport.invoke(restrictedTool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("not allowed"));
    }

    @Test
    void rejectsUrlWithCredentials() {
        ObjectNode input = fetchInput("https://user:pass@example.com/data", "prompt");
        ToolResult result = ToolTestSupport.invoke(tool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("credentials"));
    }

    @Test
    void handles404Response() throws IOException {
        HttpServer server = startServer(404, "text/html", "<h1>Not Found</h1>");
        try {
            ObjectNode input = fetchInput(serverUrl(server), "prompt");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success()); // HTTP 404 is a successful fetch
            assertTrue(result.output().contains("Status: 404"));
            assertTrue(result.output().contains("Not Found"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotUpgradeHttpForLocalAddresses() throws IOException {
        HttpServer server = startServer(200, "text/plain", "local content");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            ObjectNode input = fetchInput(url, "prompt");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success(), "Expected success but got: " + result.output());
            assertTrue(result.output().contains("local content"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void handlesRedirectSameDomain() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/target", exchange -> {
            String response = "<html><body>Final destination</body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", serverUrl(server) + "/target");
            exchange.sendResponseHeaders(301, -1);
        });
        server.start();
        try {
            ObjectNode input = fetchInput(serverUrl(server) + "/redirect", "prompt");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success());
            assertTrue(result.output().contains("Final destination"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void handlesCrossDomainRedirect() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://othersite.example/");
            // Use a non-301 status so the redirect is cross-domain
            exchange.sendResponseHeaders(302, -1);
        });
        server.start();
        try {
            ObjectNode input = fetchInput(serverUrl(server) + "/redirect", "my prompt");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success()); // still considered success — no fetch error
            assertTrue(result.output().contains("REDIRECT DETECTED"));
            assertTrue(result.output().contains("othersite.example"));
            assertTrue(result.output().contains("my prompt"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crossDomainRedirectSurvivesBodyCloseFailure() {
        WebFetchTool redirectTool = new WebFetchTool(
                new RedirectingHttpClient(new CloseFailingInputStream()),
                new NetworkAccessPolicy(false));

        ToolResult result = ToolTestSupport.invoke(
                redirectTool,
                fetchInput("https://example.com/redirect", "my prompt"),
                context);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("REDIRECT DETECTED"));
        assertTrue(result.output().contains("othersite.example"));
    }

    @Test
    void includesTimingAndSizeInfo() throws IOException {
        HttpServer server = startServer(200, "text/plain", "hello");
        try {
            ObjectNode input = fetchInput(serverUrl(server), "prompt");
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success());
            String output = result.output();
            assertTrue(output.contains("Size:"));
            assertTrue(output.contains("Duration:"));
            assertTrue(output.contains("ms"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncatesLongContentWithNotice() throws IOException {
        String longBody = "x".repeat(120_000);
        HttpServer server = startServer(200, "text/plain", longBody);
        try {
            ObjectNode input = fetchInput(serverUrl(server));
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success());
            assertTrue(result.output().contains("[Content truncated at 100000 characters]"));
            assertTrue(result.output().contains("Note: Content was truncated to 100000 characters."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncatesLargeMultibyteContentWithoutSubstringFailure() throws IOException {
        String longBody = "你".repeat(120_000);
        HttpServer server = startServer(200, "text/plain; charset=UTF-8", longBody);
        try {
            ObjectNode input = fetchInput(serverUrl(server));
            ToolResult result = ToolTestSupport.invoke(tool, input, context);

            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("[Content truncated at 100000 characters]"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emitsFetchingAndReceivedProgress() throws IOException {
        HttpServer server = startServer(200, "text/plain", "hello world");
        List<String> progress = new ArrayList<>();
        context.session().addListener(new SessionListener() {
            @Override
            public void onToolExecutionMetric(String toolUseId, String metricText) {
                progress.add(metricText);
            }
        });

        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_fetch");
        try {
            ToolResult result = ToolTestSupport.invoke(tool, fetchInput(serverUrl(server)), context);
            assertTrue(result.success());
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
            server.stop(0);
        }

        assertTrue(progress.stream().anyMatch(p -> p.startsWith("Fetching ")), progress.toString());
        assertTrue(progress.stream().anyMatch(p -> p.startsWith("Received ")), progress.toString());
    }

    @Test
    void emitsExtractingProgressForLargeHtmlResponses() throws IOException {
        String html = "<html><body>" + "x".repeat(70_000) + "</body></html>";
        HttpServer server = startServer(200, "text/html", html);
        List<String> progress = new ArrayList<>();
        context.session().addListener(new SessionListener() {
            @Override
            public void onToolExecutionMetric(String toolUseId, String metricText) {
                progress.add(metricText);
            }
        });

        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_fetch");
        try {
            ToolResult result = ToolTestSupport.invoke(tool, fetchInput(serverUrl(server)), context);
            assertTrue(result.success());
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
            server.stop(0);
        }

        assertTrue(progress.stream().anyMatch("Extracting content"::equals), progress.toString());
    }

    // ---- helpers ----

    private ObjectNode fetchInput(String url, String prompt) {
        ObjectNode input = mapper.createObjectNode();
        input.put("url", url);
        input.put("prompt", prompt);
        return input;
    }

    private ObjectNode fetchInput(String url) {
        ObjectNode input = mapper.createObjectNode();
        input.put("url", url);
        return input;
    }

    private HttpServer startServer(int statusCode, String contentType, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private String serverUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final class CloseFailingInputStream extends InputStream {
        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            throw new IOException("close failed");
        }
    }

    private static final class RedirectingHttpClient extends HttpClient {
        private final InputStream body;

        private RedirectingHttpClient(InputStream body) {
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) redirectResponse(request, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        private static HttpResponse<InputStream> redirectResponse(HttpRequest request, InputStream body) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return 302;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<InputStream>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(
                            Map.of("Location", List.of("https://othersite.example/")),
                            (name, value) -> true);
                }

                @Override
                public InputStream body() {
                    return body;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }
    }
}
