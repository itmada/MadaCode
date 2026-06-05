package madacode.services.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.core.model.ToolCall;
import madacode.logging.DiagnosticEventLogger;
import madacode.provider.ActiveState;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import madacode.tool.Tool;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MadaApiClient implements ApiClient {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final java.time.Duration DEFAULT_TIMEOUT = java.time.Duration.ofSeconds(300);
    private static final String FINE_GRAINED_TOOL_STREAMING_BETA =
            "fine-grained-tool-streaming-2025-05-14";

    private final ProviderRegistry registry;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final AnthropicMessageSerializer serializer;

    public MadaApiClient(ProviderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.mapper = new ObjectMapper();
        this.serializer = new AnthropicMessageSerializer(mapper);
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
            DiagnosticEventLogger.apiRequest(modelName, messages.size());

            // Fine-grained tool streaming (FGTS) is only correctly implemented by the
            // first-party Anthropic API. Proxies/relays and Bedrock/Vertex either reject
            // the beta with 400 or silently stop emitting input_json_delta events for
            // large tool inputs, yielding empty ({}) tool arguments on big bash/write
            // calls. Gate on the provider's declared capability instead of enabling it
            // unconditionally.
            boolean fineGrainedToolStreaming = provider.supportsFineGrainedToolStreaming();

            String requestBody = serializer.buildRequestBody(
                    modelName, DEFAULT_MAX_TOKENS, messages, systemPrompt, tools,
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
            DiagnosticEventLogger.apiResponse(response.statusCode(), elapsed);

            Stream<String> responseBody = response.body();
            // Close the stream on cancellation so the blocking iterator in
            // parseStreamingResponse unblocks immediately instead of waiting
            // for the next SSE line from the server.
            cancellationToken.onCancel(responseBody::close);

            try (Stream<String> responseLines = responseBody) {
                if (response.statusCode() != 200) {
                    List<String> rawResponseLines = responseLines.toList();
                    DiagnosticEventLogger.apiModelResponseFull(
                            modelName, response.statusCode(), rawResponseLines);
                    String body = rawResponseLines.stream().collect(Collectors.joining(System.lineSeparator()));
                    DiagnosticEventLogger.apiError(response.statusCode(), truncate(body, 200));
                    throw ApiClientException.http(response.statusCode(), body);
                }

                List<String> rawResponseLines = new ArrayList<>();
                try {
                    return parseStreamingResponse(
                            responseLines, sink, start, cancellationToken, tools, rawResponseLines);
                } finally {
                    DiagnosticEventLogger.apiModelResponseFull(
                            modelName, response.statusCode(), rawResponseLines);
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

    ApiResponse parseStreamingResponse(
            Stream<String> responseLines,
            ApiStreamSink sink,
            long requestStartNanos,
            CancellationToken cancellationToken) {
        return parseStreamingResponse(responseLines, sink, requestStartNanos, cancellationToken, List.of());
    }

    ApiResponse parseStreamingResponse(
            Stream<String> responseLines,
            ApiStreamSink sink,
            long requestStartNanos,
            CancellationToken cancellationToken,
            Collection<Tool<?>> tools) {
        return parseStreamingResponse(responseLines, sink, requestStartNanos,
                cancellationToken, tools, null);
    }

    ApiResponse parseStreamingResponse(
            Stream<String> responseLines,
            ApiStreamSink sink,
            long requestStartNanos,
            CancellationToken cancellationToken,
            Collection<Tool<?>> tools,
            List<String> rawResponseLines) {
        StreamState state = new StreamState(requestStartNanos);
        Map<String, Set<String>> requiredFieldsByTool = requiredFieldsByTool(tools);

        Iterator<String> lines = responseLines.iterator();
        while (lines.hasNext()) {
            if (cancellationToken.isCancelled()) {
                throw new madacode.core.turn.CancellationException(cancellationToken.reason());
            }
            String line = lines.next();
            if (rawResponseLines != null) {
                rawResponseLines.add(line);
            }
            if (!line.startsWith("data:")) continue;
            String data = line.substring("data:".length()).trim();
            if (!data.isEmpty()) {
                handleStreamingData(data, state, sink, requiredFieldsByTool);
            }
        }

        long totalMs = state.elapsedMs();
        sink.onMessageStop(state.stopReason, state.usage, state.ttftMs, totalMs);

        return new ApiResponse(
                state.textBuilder.toString(),
                state.toolCalls,
                state.stopReason,
                state.usage);
    }

    private void handleStreamingData(
            String data,
            StreamState state,
            ApiStreamSink sink,
            Map<String, Set<String>> requiredFieldsByTool) {
        try {
            JsonNode event = mapper.readTree(data);
            String type = event.path("type").asText();

            switch (type) {
                case "message_start" -> {
                    JsonNode message = event.path("message");
                    String model = message.path("model").asText("");
                    TokenUsage initial = parseUsage(message.path("usage"), TokenUsage.ZERO);
                    state.usage = initial;
                    sink.onMessageStart(model, initial);
                }
                case "content_block_start" -> {
                    JsonNode block = event.path("content_block");
                    String blockType = block.path("type").asText();
                    if ("tool_use".equals(blockType)) {
                        int index = event.path("index").asInt();
                        ToolUseAccumulator accumulator = new ToolUseAccumulator(
                                block.path("id").asText(),
                                block.path("name").asText());
                        JsonNode input = block.get("input");
                        if (input != null && !input.isMissingNode() && !input.isNull()) {
                            accumulator.initialInputJson = mapper.writeValueAsString(input);
                        }
                        state.toolUseBlocks.put(index, accumulator);
                    } else if ("thinking".equals(blockType)) {
                        int index = event.path("index").asInt();
                        state.thinkingBlocks.put(index, new StringBuilder());
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    String deltaType = delta.path("type").asText();
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText();
                        if (!text.isEmpty()) {
                            state.recordFirstTokenIfNeeded();
                            state.textBuilder.append(text);
                            sink.onTextDelta(text);
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        int index = event.path("index").asInt();
                        ToolUseAccumulator accumulator = state.toolUseBlocks.get(index);
                        if (accumulator != null) {
                            state.recordFirstTokenIfNeeded();
                            String partialJson = delta.path("partial_json").asText("");
                            accumulator.deltaCount++;
                            accumulator.inputJson.append(partialJson);
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        int index = event.path("index").asInt();
                        StringBuilder sb = state.thinkingBlocks.get(index);
                        if (sb != null) {
                            String thinking = delta.path("thinking").asText();
                            if (!thinking.isEmpty()) {
                                sb.append(thinking);
                            }
                        }
                    }
                }
                case "content_block_stop" -> {
                    int index = event.path("index").asInt();
                    ToolUseAccumulator accumulator = state.toolUseBlocks.remove(index);
                    if (accumulator != null) {
                        ObjectNode input = extractToolCallInput(
                                accumulator,
                                requiredFieldsByTool.getOrDefault(accumulator.name, Set.of()));
                        state.toolCalls.add(new ToolCall(accumulator.id, accumulator.name, input));
                        sink.onToolUseBlock(new ContentBlock.ToolUseBlock(
                                accumulator.id, accumulator.name, input));
                    }
                    StringBuilder thinkingSb = state.thinkingBlocks.remove(index);
                    if (thinkingSb != null) {
                        sink.onThinkingBlock(new ContentBlock.ThinkingBlock(thinkingSb.toString()));
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = event.path("delta");
                    String wireStop = delta.path("stop_reason").asText(null);
                    if (wireStop != null && !wireStop.isEmpty()) {
                        state.stopReason = StopReason.parse(wireStop);
                    }
                    JsonNode usageNode = event.path("usage");
                    if (usageNode.isObject()) {
                        state.usage = parseUsage(usageNode, state.usage);
                    }
                }
                case "message_stop" -> { /* handled after stream drains */ }
                case "error" -> {
                    String message = event.path("error").path("message").asText("Unknown stream error");
                    throw new ApiClientException(message);
                }
                default -> { /* ping and forward-compat events are ignored */ }
            }
        } catch (ApiClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiClientException("Failed to parse Anthropic stream event: " + data, e);
        }
    }

    private static TokenUsage parseUsage(JsonNode node, TokenUsage running) {
        if (!node.isObject()) return running;
        int input = node.has("input_tokens")
                ? node.path("input_tokens").asInt() : running.inputTokens();
        int output = node.has("output_tokens")
                ? node.path("output_tokens").asInt() : running.outputTokens();
        int cacheCreate = node.has("cache_creation_input_tokens")
                ? node.path("cache_creation_input_tokens").asInt() : running.cacheCreationTokens();
        int cacheRead = node.has("cache_read_input_tokens")
                ? node.path("cache_read_input_tokens").asInt() : running.cacheReadTokens();
        return new TokenUsage(input, output, cacheCreate, cacheRead);
    }

    private static final class StreamState {
        final long requestStartNanos;
        final StringBuilder textBuilder = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
        final Map<Integer, ToolUseAccumulator> toolUseBlocks = new HashMap<>();
        final Map<Integer, StringBuilder> thinkingBlocks = new HashMap<>();
        TokenUsage usage = TokenUsage.ZERO;
        StopReason stopReason = StopReason.UNKNOWN;
        long ttftMs = -1;

        StreamState(long requestStartNanos) { this.requestStartNanos = requestStartNanos; }

        void recordFirstTokenIfNeeded() { if (ttftMs < 0) ttftMs = elapsedMs(); }

        long elapsedMs() {
            return java.time.Duration.ofNanos(System.nanoTime() - requestStartNanos).toMillis();
        }
    }

    private ObjectNode extractToolCallInput(
            ToolUseAccumulator accumulator,
            Set<String> requiredFields) throws Exception {
        String inputJson = accumulator.deltaCount > 0
                ? accumulator.inputJson.toString()
                : accumulator.initialInputJson;
        boolean missingInputStream = inputJson.isBlank();
        DiagnosticEventLogger.apiToolInputStream(
                accumulator.name,
                accumulator.id,
                accumulator.deltaCount,
                inputJson.length(),
                missingInputStream);
        if (missingInputStream) {
            if (requiredFields == null || requiredFields.isEmpty()) {
                return mapper.createObjectNode();
            }
            throw new ApiClientException(
                    "Tool input stream ended without JSON input for "
                            + accumulator.name + " (id=" + accumulator.id
                            + ", required=" + requiredFields + "). "
                            + "This usually means the provider did not stream tool arguments "
                            + "for the tool call. Use a compatible Anthropic endpoint or split large writes.");
        }
        JsonNode inputNode;
        try {
            inputNode = mapper.readTree(inputJson);
        } catch (Exception e) {
            DiagnosticEventLogger.apiToolInputJsonParseFailed(
                    accumulator.name, accumulator.id, inputJson.length());
            throw new ApiClientException(
                    "Failed to parse streamed tool input JSON for "
                            + accumulator.name + " (id=" + accumulator.id
                            + ", chars=" + inputJson.length() + "): "
                            + e.getMessage(),
                    e);
        }
        if (!inputNode.isObject()) {
            throw new ApiClientException("Tool input must be a JSON object: " + inputJson);
        }
        ObjectNode inputObject = ((ObjectNode) inputNode).deepCopy();
        Set<String> missingRequired = missingRequiredFields(inputObject, requiredFields);
        if (!missingRequired.isEmpty()) {
            throw new ApiClientException(
                    "Tool input stream ended without required JSON fields for "
                            + accumulator.name + " (id=" + accumulator.id
                            + ", missing=" + missingRequired + ", chars=" + inputJson.length()
                            + ", deltas=" + accumulator.deltaCount + "). "
                            + "This usually means the provider dropped or truncated tool arguments "
                            + "for the tool call. Use a compatible Anthropic endpoint or split large writes.");
        }
        return inputObject;
    }

    private Map<String, Set<String>> requiredFieldsByTool(Collection<Tool<?>> tools) {
        Map<String, Set<String>> result = new HashMap<>();
        if (tools == null) {
            return result;
        }
        for (Tool<?> tool : tools) {
            if (tool == null) {
                continue;
            }
            JsonNode required = tool.inputSchema(mapper).path("required");
            Set<String> fields = new HashSet<>();
            if (required.isArray()) {
                for (JsonNode field : required) {
                    String name = field.asText("");
                    if (!name.isBlank()) {
                        fields.add(name);
                    }
                }
            }
            result.put(tool.name(), Set.copyOf(fields));
        }
        return result;
    }

    private static Set<String> missingRequiredFields(ObjectNode input, Set<String> requiredFields) {
        if (requiredFields == null || requiredFields.isEmpty()) {
            return Set.of();
        }
        Set<String> missing = new HashSet<>();
        for (String field : requiredFields) {
            JsonNode value = input.get(field);
            if (value == null || value.isNull()) {
                missing.add(field);
            }
        }
        return Set.copyOf(missing);
    }

    private static final class ToolUseAccumulator {
        private final String id;
        private final String name;
        private final StringBuilder inputJson = new StringBuilder();
        private String initialInputJson = "";
        private int deltaCount;

        private ToolUseAccumulator(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
