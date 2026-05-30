package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebFetchTool implements Tool<WebFetchTool.Input> {

    public record Input(String url, String prompt) {}

    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final int MAX_REDIRECTS = 10;
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int READ_TIMEOUT_SEC = 30;
    private static final int EXTRACTION_PROGRESS_THRESHOLD_BYTES = 64 * 1024;
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([^;]+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final NetworkAccessPolicy networkAccessPolicy;

    public WebFetchTool() {
        this(true);
    }

    WebFetchTool(boolean enforceNetworkRestrictions) {
        this(new NetworkAccessPolicy(enforceNetworkRestrictions));
    }

    WebFetchTool(NetworkAccessPolicy networkAccessPolicy) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                networkAccessPolicy);
    }

    WebFetchTool(HttpClient httpClient, NetworkAccessPolicy networkAccessPolicy) {
        this.httpClient = httpClient;
        this.networkAccessPolicy = networkAccessPolicy;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetches content from a URL and converts HTML to plain text. "
                + "This tool does not interpret or execute prompt instructions. "
                + "IMPORTANT: web_fetch will fail for authenticated or private URLs.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("url", ToolSchemas.stringProperty(
                mapper, "The URL to fetch content from"));
        properties.set("prompt", ToolSchemas.stringProperty(
                mapper, "Optional prompt. Note: prompt is not interpreted by web_fetch."));
        return ToolSchemas.objectSchema(mapper, properties, "url");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String rawUrl = input.url() == null ? "" : input.url();
        String prompt = input.prompt() == null ? "" : input.prompt();
        ProgressEmitter progress = new ProgressEmitter(context.session(), 500);

        // ---- 1. URL validation ----
        if (rawUrl == null || rawUrl.isBlank()) {
            return new ToolResult(name(), false, "URL is required");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            return new ToolResult(name(), false, "URL exceeds maximum length of " + MAX_URL_LENGTH);
        }

        NetworkAccessPolicy.ValidationResult urlValidation = networkAccessPolicy.validate(rawUrl);
        if (!urlValidation.isValid()) {
            return new ToolResult(name(), false, urlValidation.error());
        }

        URI url = urlValidation.uri();
        progress.emitMetric("Fetching " + url);

        // ---- 2. Fetch with redirect handling ----
        long startTime = System.currentTimeMillis();
        FetchResult fetchResult;
        try {
            fetchResult = fetchWithRedirectHandling(url, 0);
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to fetch URL: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(name(), false, "Fetch was interrupted");
        }

        long durationMs = System.currentTimeMillis() - startTime;

        if (fetchResult.isRedirect()) {
            return new ToolResult(name(), true,
                    formatRedirectMessage(fetchResult, rawUrl, prompt));
        }

        int byteLen = fetchResult.byteLength();
        progress.emitMetric("Received " + formatBytes(byteLen) + " · " + fetchResult.contentType());

        // ---- 3. HTML → text conversion ----
        if (byteLen > EXTRACTION_PROGRESS_THRESHOLD_BYTES
                && fetchResult.contentType() != null
                && fetchResult.contentType().contains("text/html")) {
            progress.emitMetric("Extracting content");
        }
        String processed = convertToText(fetchResult.body(), fetchResult.contentType());

        // ---- 4. Truncate ----
        boolean truncated = processed.length() > MAX_OUTPUT_CHARS;
        if (truncated) {
            processed = processed.substring(0, MAX_OUTPUT_CHARS)
                    + "\n\n[Content truncated at " + MAX_OUTPUT_CHARS + " characters]";
        }

        // ---- 5. Format result ----
        return new ToolResult(name(), true, formatResult(
                rawUrl, fetchResult.statusCode(), fetchResult.contentType(),
                byteLen, processed, durationMs, truncated, prompt));
    }

    // ---- HTTP fetch ----

    private FetchResult fetchWithRedirectHandling(URI url, int depth)
            throws IOException, InterruptedException {
        if (depth > MAX_REDIRECTS) {
            throw new IOException("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
                .header("Accept", "text/html, text/plain, */*")
                .header("User-Agent", "MadaCode/0.1")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IOException("HTTP request failed for " + url + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();

        // Handle redirects
        if (status == 301 || status == 302 || status == 307 || status == 308) {
            try {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new IOException("Redirect missing Location header");
                }

                URI redirectUri = url.resolve(location);
                NetworkAccessPolicy.ValidationResult redirectValidation = networkAccessPolicy.validate(redirectUri);
                if (!redirectValidation.isValid()) {
                    throw new IOException(redirectValidation.error());
                }
                URI validatedRedirect = redirectValidation.uri();

                if (isSameDomainRedirect(url, validatedRedirect)) {
                    return fetchWithRedirectHandling(validatedRedirect, depth + 1);
                }
                // Cross-domain redirect — return as redirect info for the model to re-request
                return FetchResult.redirect(url.toString(), validatedRedirect.toString(), status, getStatusText(status));
            } finally {
                closeResponseBody(response);
            }
        }

        ReadBodyResult readBody;
        try (InputStream bodyStream = response.body()) {
            readBody = readBody(bodyStream);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("unknown");
        String body = decodeBody(readBody.bytes(), contentType);
        if (readBody.truncated()) {
            body += "\n\n[Response truncated at 10 MB]";
        }
        return FetchResult.success(status, getStatusText(status), contentType, body, readBody.bytes().length);
    }

    private void closeResponseBody(HttpResponse<InputStream> response) {
        InputStream body = response.body();
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // Cleanup failures should not replace the HTTP result or redirect decision.
            }
        }
    }

    private boolean isSameDomainRedirect(URI originalUrl, URI redirectUrl) {
        try {
            String origHost = originalUrl.getHost();
            String redirHost = redirectUrl.getHost();
            if (origHost == null || redirHost == null) {
                return false;
            }
            return stripWww(origHost).equals(stripWww(redirHost));
        } catch (Exception e) {
            return false;
        }
    }

    private String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private ReadBodyResult readBody(InputStream bodyStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int remaining = MAX_RESPONSE_BYTES;
        boolean truncated = false;
        while (remaining > 0) {
            int read = bodyStream.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(chunk, 0, read);
            remaining -= read;
        }
        if (bodyStream.read() != -1) {
            truncated = true;
        }
        return new ReadBodyResult(out.toByteArray(), truncated);
    }

    private String decodeBody(byte[] bodyBytes, String contentType) {
        Charset charset = extractCharset(contentType);
        return new String(bodyBytes, charset);
    }

    private Charset extractCharset(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1).trim().replace("\"", ""));
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---- HTML → text ----

    private String convertToText(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }

        if (contentType != null && contentType.contains("text/html")) {
            Document doc = Jsoup.parse(body);
            // Remove script, style, noscript
            doc.select("script, style, noscript, nav, footer, header").remove();
            // Get title
            String title = doc.title();
            // Extract body text with basic structure
            String text = Jsoup.clean(doc.html(), Safelist.none())
                    .replaceAll("(?m)^[ \t]+", "")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();
            if (!title.isBlank()) {
                text = "Title: " + title + "\n\n" + text;
            }
            return text;
        }

        return body;
    }

    // ---- Result formatting ----

    private String formatResult(
            String url, int code, String contentType, int bytes,
            String content, long durationMs, boolean truncated, String prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append('\n');
        sb.append("Status: ").append(code).append('\n');
        sb.append("Content-Type: ").append(contentType).append('\n');
        sb.append("Size: ").append(formatBytes(bytes)).append('\n');
        sb.append("Duration: ").append(durationMs).append("ms\n");
        if (prompt != null && !prompt.isBlank()) {
            sb.append("Note: prompt is not interpreted by web_fetch; returned content is raw extracted text.\n");
        }
        if (truncated) {
            sb.append("Note: Content was truncated to ").append(MAX_OUTPUT_CHARS)
                    .append(" characters.\n");
        }
        sb.append('\n');
        sb.append(content);
        return sb.toString();
    }

    private String formatRedirectMessage(FetchResult result, String originalUrl, String prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("REDIRECT DETECTED: The URL redirects to a different host.\n\n")
                .append("Original URL: ").append(originalUrl).append('\n')
                .append("Redirect URL: ").append(result.redirectUrl()).append('\n')
                .append("Status: ").append(result.statusCode()).append(' ').append(result.statusText()).append("\n\n")
                .append("To continue, call web_fetch again with:\n")
                .append("  url: \"").append(result.redirectUrl()).append("\"");
        if (prompt != null && !prompt.isBlank()) {
            sb.append('\n').append("  prompt: \"").append(prompt).append("\"");
        }
        return sb.toString();
    }

    private String getStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "HTTP " + code;
        };
    }

    private String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ---- Internal types ----

    private record FetchResult(
            String body,
            int byteLength,
            int statusCode,
            String statusText,
            String contentType,
            String originalUrl,
            String redirectUrl) {

        private static FetchResult success(
                int code, String statusText, String contentType, String body, int byteLength) {
            return new FetchResult(body, byteLength, code, statusText, contentType, null, null);
        }

        private static FetchResult redirect(
                String originalUrl, String redirectUrl, int code, String statusText) {
            return new FetchResult(null, 0, code, statusText, null, originalUrl, redirectUrl);
        }

        boolean isRedirect() {
            return redirectUrl != null;
        }
    }

    private record ReadBodyResult(byte[] bytes, boolean truncated) {
    }
}
