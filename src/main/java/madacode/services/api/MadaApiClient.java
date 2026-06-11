package madacode.services.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.core.turn.CancellationToken;
import madacode.core.model.Message;
import madacode.logging.DefaultDiagnosticEvents;
import madacode.logging.DiagnosticEvents;
import madacode.logging.ModelResponseLogWriter;
import madacode.provider.ActiveState;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import madacode.tool.Tool;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MadaApiClient implements ApiClient {

    private static final int DEFAULT_MAX_TOKENS = 32_000;
    private static final java.time.Duration DEFAULT_TIMEOUT = java.time.Duration.ofSeconds(300);
    private static final String FINE_GRAINED_TOOL_STREAMING_BETA =
            "fine-grained-tool-streaming-2025-05-14";

    private final ProviderRegistry registry;
    private final HttpClient httpClient;
    private final AnthropicMessageSerializer serializer;
    private final AnthropicStreamParser streamParser;
    private final ModelResponseLogWriter modelResponseLogWriter;
    private final DiagnosticEvents diagnosticEvents;
    private final int maxTokens;

    public MadaApiClient(ProviderRegistry registry, ModelResponseLogWriter modelResponseLogWriter) {
        this(registry, modelResponseLogWriter, DEFAULT_MAX_TOKENS, new DefaultDiagnosticEvents());
    }

    public MadaApiClient(
            ProviderRegistry registry,
            ModelResponseLogWriter modelResponseLogWriter,
            int maxTokens,
            DiagnosticEvents diagnosticEvents) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.modelResponseLogWriter = Objects.requireNonNull(
                modelResponseLogWriter, "modelResponseLogWriter");
        this.diagnosticEvents = Objects.requireNonNull(diagnosticEvents, "diagnosticEvents");
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        this.serializer = new AnthropicMessageSerializer(mapper);
        this.streamParser = new AnthropicStreamParser(mapper, diagnosticEvents);
    }

    /**
     * Each call reads the latest {@link ProviderRegistry#active()} snapshot.
     * Wrapped retries (see {@link RetryingApiClient}) therefore pick up
     * provider changes that happened between attempts — this is intentional:
     * if the user switched provider mid-retry, they expect subsequent attempts
     * to use the new endpoint. The {@link ActiveState} record is immutable, so
     * within a single {@code send()} the snapshot is consistent.
     */
    @Override
    public ApiResponse send(
            List<Message> messages,
            String systemPrompt,
            Collection<Tool<?>> tools,
            ApiStreamSink sink,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellationToken, "cancellationToken");

        if (cancellationToken.isCancelled()) {
            throw new madacode.core.turn.CancellationException(cancellationToken.reason());
        }

        try {
            ActiveState state = registry.active();
            Provider provider = state.provider();
            String modelName = state.currentModel().name();

            long start = System.nanoTime();
            diagnosticEvents.apiRequest(modelName, messages.size(), maxTokens);

            // Fine-grained tool streaming (FGTS) is only correctly implemented by the
            // first-party Anthropic API. Proxies/relays and Bedrock/Vertex either reject
            // the beta with 400 or silently stop emitting input_json_delta events for
            // large tool inputs, yielding empty ({}) tool arguments on big bash/write
            // calls. Gate on the provider's declared capability instead of enabling it
            // unconditionally.
            boolean fineGrainedToolStreaming = provider.supportsFineGrainedToolStreaming();

            String requestBody = serializer.buildRequestBody(
                    modelName, maxTokens, messages, systemPrompt, tools,
                    fineGrainedToolStreaming);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(provider.messagesUrl())
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + provider.authToken())
                    .header("anthropic-version", "2023-06-01");
            if (fineGrainedToolStreaming) {
                requestBuilder.header("anthropic-beta", FINE_GRAINED_TOOL_STREAMING_BETA);
            }
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            CompletableFuture<HttpResponse<Stream<String>>> future = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofLines());
            cancellationToken.onCancel(() -> future.cancel(true));

            HttpResponse<Stream<String>> response;
            try {
                response = future.join();
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw new madacode.core.turn.CancellationException(cancellationToken.reason());
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cancellationToken.isCancelled()) {
                    throw new madacode.core.turn.CancellationException(cancellationToken.reason());
                }
                throw new ApiClientException("Failed to call Anthropic API", cause);
            }

            long elapsed = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
            diagnosticEvents.apiResponse(response.statusCode(), elapsed);

            Stream<String> responseBody = response.body();
            // Close the stream on cancellation so the SSE line iterator
            // unblocks immediately instead of waiting for another event.
            cancellationToken.onCancel(responseBody::close);

            try (Stream<String> responseLines = responseBody) {
                boolean logFullModelResponse = modelResponseLogWriter.isEnabled();
                if (response.statusCode() != 200) {
                    List<String> rawResponseLines = responseLines.toList();
                    if (logFullModelResponse) {
                        logModelResponseFull(modelName, response.statusCode(), rawResponseLines);
                    }
                    String body = rawResponseLines.stream().collect(Collectors.joining(System.lineSeparator()));
                    diagnosticEvents.apiError(response.statusCode(), truncate(body, 200));
                    throw ApiClientException.http(response.statusCode(), body);
                }

                List<String> rawResponseLines = logFullModelResponse
                        ? new ArrayList<>()
                        : null;
                try {
                    return streamParser.parse(new AnthropicStreamParser.ParseRequest(
                            responseLines,
                            sink,
                            start,
                            cancellationToken,
                            tools,
                            rawResponseLines));
                } finally {
                    if (rawResponseLines != null) {
                        logModelResponseFull(modelName, response.statusCode(), rawResponseLines);
                    }
                }
            }

        } catch (ApiClientException | madacode.core.turn.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiClientException("Failed to call Anthropic API", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private void logModelResponseFull(String modelName, int statusCode, List<String> rawResponseLines) {
        String body = String.join(System.lineSeparator(), rawResponseLines);
        diagnosticEvents.apiModelResponseFull(
                modelName,
                statusCode,
                rawResponseLines.size(),
                body.length(),
                modelResponseLogWriter.write(modelName, statusCode, rawResponseLines));
    }
}
