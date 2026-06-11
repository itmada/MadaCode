package madacode.services.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.core.model.ToolCall;
import madacode.core.turn.CancellationToken;
import madacode.logging.DiagnosticEvents;
import madacode.tool.Tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

final class AnthropicStreamParser {

    private final ObjectMapper mapper;
    private final DiagnosticEvents diagnosticEvents;

    AnthropicStreamParser(ObjectMapper mapper, DiagnosticEvents diagnosticEvents) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.diagnosticEvents = Objects.requireNonNull(diagnosticEvents, "diagnosticEvents");
    }

    ApiClient.ApiResponse parse(ParseRequest request) {
        Objects.requireNonNull(request, "request");
        StreamState state = new StreamState(request.requestStartNanos());
        Map<String, Set<String>> requiredFieldsByTool = requiredFieldsByTool(request.tools());

        Iterator<String> lines = request.responseLines().iterator();
        while (lines.hasNext()) {
            if (request.cancellationToken().isCancelled()) {
                throw new madacode.core.turn.CancellationException(
                        request.cancellationToken().reason());
            }
            String line = lines.next();
            if (request.rawResponseLines() != null) {
                request.rawResponseLines().add(line);
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).trim();
            if (!data.isEmpty()) {
                handleStreamingData(data, state, request.sink(), requiredFieldsByTool);
            }
        }

        long totalMs = state.elapsedMs();
        request.sink().onMessageStop(state.stopReason, state.usage, state.ttftMs, totalMs);
        return new ApiClient.ApiResponse(
                state.textBuilder.toString(),
                state.toolCalls,
                state.stopReason,
                state.usage);
    }

    record ParseRequest(
            Stream<String> responseLines,
            ApiStreamSink sink,
            long requestStartNanos,
            CancellationToken cancellationToken,
            Collection<Tool<?>> tools,
            List<String> rawResponseLines) {

        ParseRequest {
            Objects.requireNonNull(responseLines, "responseLines");
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(cancellationToken, "cancellationToken");
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
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
                case "message_stop" -> {
                    // handled after the stream drains
                }
                case "error" -> {
                    String message = event.path("error").path("message").asText("Unknown stream error");
                    throw new ApiClientException(message);
                }
                default -> {
                    // ping and forward-compat events are ignored
                }
            }
        } catch (ApiClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiClientException("Failed to parse Anthropic stream event: " + data, e);
        }
    }

    private ObjectNode extractToolCallInput(
            ToolUseAccumulator accumulator,
            Set<String> requiredFields) throws Exception {
        String inputJson = accumulator.deltaCount > 0
                ? accumulator.inputJson.toString()
                : accumulator.initialInputJson;
        boolean missingInputStream = inputJson.isBlank();
        diagnosticEvents.apiToolInputStream(
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
                            + toolInputFailureHint());
        }
        JsonNode inputNode;
        try {
            inputNode = mapper.readTree(inputJson);
        } catch (Exception e) {
            diagnosticEvents.apiToolInputJsonParseFailed(
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
                            + toolInputFailureHint());
        }
        return inputObject;
    }

    private Map<String, Set<String>> requiredFieldsByTool(Collection<Tool<?>> tools) {
        Map<String, Set<String>> result = new HashMap<>();
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

    private static TokenUsage parseUsage(JsonNode node, TokenUsage running) {
        if (!node.isObject()) {
            return running;
        }
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

    private static String toolInputFailureHint() {
        return "This can happen when max_tokens is too low, the provider truncated tool arguments, "
                + "or the endpoint does not stream tool input deltas correctly. "
                + "Increase MADA_MAX_OUTPUT_TOKENS if the tool input is large, use a compatible "
                + "Anthropic endpoint, or split large writes.";
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

        StreamState(long requestStartNanos) {
            this.requestStartNanos = requestStartNanos;
        }

        void recordFirstTokenIfNeeded() {
            if (ttftMs < 0) {
                ttftMs = elapsedMs();
            }
        }

        long elapsedMs() {
            return Duration.ofNanos(System.nanoTime() - requestStartNanos).toMillis();
        }
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
