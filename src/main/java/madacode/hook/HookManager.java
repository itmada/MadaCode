package madacode.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class HookManager {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final HookConfig config;
    private final ObjectMapper mapper;

    public HookManager(Path configPath) {
        this.mapper = new ObjectMapper();
        HookConfig loaded;
        try {
            if (Files.isRegularFile(configPath)) {
                loaded = mapper.readValue(configPath.toFile(), HookConfig.class);
            } else {
                loaded = HookConfig.empty();
            }
        } catch (IOException e) {
            loaded = HookConfig.empty();
        }
        this.config = loaded;
    }

    /**
     * Run PRE_TOOL_USE hooks. Returns the effective input after hooks,
     * or throws if a blocking hook denies the tool.
     */
    public PreToolUseResult runPreToolUse(String toolName, ObjectNode toolInput, String sessionId) {
        for (var entry : config.forEvent(HookEvent.PRE_TOOL_USE)) {
            ObjectNode eventData = buildEventData(toolName, toolInput, sessionId, "PRE_TOOL_USE");
            HookResult result = executeHook(entry.command(), entry.timeoutMs(), eventData);

            if (!result.allowed()) {
                if (entry.blockOnFailure()) {
                    return PreToolUseResult.deny(result.output());
                }
                continue;
            }

            if (result.modifiedInput() != null) {
                toolInput = result.modifiedInput();
            }
        }
        return PreToolUseResult.allow(toolInput);
    }

    /**
     * Run POST_TOOL_USE hooks. Results are fire-and-forget (no blocking).
     */
    public void runPostToolUse(String toolName, ObjectNode toolInput, String sessionId, boolean success, String output) {
        for (var entry : config.forEvent(HookEvent.POST_TOOL_USE)) {
            ObjectNode eventData = buildEventData(toolName, toolInput, sessionId, "POST_TOOL_USE");
            eventData.put("success", success);
            eventData.put("toolOutput", output != null ? output : "");
            executeHook(entry.command(), entry.timeoutMs(), eventData);
        }
    }

    private ObjectNode buildEventData(String toolName, ObjectNode toolInput, String sessionId, String eventType) {
        ObjectNode data = mapper.createObjectNode();
        data.put("event", eventType);
        data.put("toolName", toolName);
        data.set("toolInput", toolInput.deepCopy());
        data.put("sessionId", sessionId);
        return data;
    }

    private HookResult executeHook(String command, long timeoutMs, ObjectNode eventData) {
        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        try {
            ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Send event data as JSON on stdin
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(mapper.writeValueAsBytes(eventData));
                stdin.flush();
            }

            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new HookResult(true, null, "Hook timed out");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (output.isEmpty()) {
                return new HookResult(true, null, null);
            }

            try {
                JsonNode response = mapper.readTree(output);
                boolean allowed = response.path("allowed").asBoolean(true);
                String reason = response.has("reason") ? response.path("reason").asText() : null;
                ObjectNode modifiedInput = response.has("modifiedInput")
                        ? (ObjectNode) response.get("modifiedInput")
                        : null;
                return new HookResult(allowed, modifiedInput, reason);
            } catch (IOException e) {
                // Non-JSON output — allow the tool, pass output as message
                return new HookResult(true, null, output);
            }
        } catch (Exception e) {
            return new HookResult(true, null, "Hook execution failed: " + e.getMessage());
        }
    }

    private record HookResult(boolean allowed, ObjectNode modifiedInput, String output) {
    }

    public record PreToolUseResult(boolean allowed, String denialReason, ObjectNode effectiveInput) {
        static PreToolUseResult allow(ObjectNode input) {
            return new PreToolUseResult(true, null, input);
        }

        static PreToolUseResult deny(String reason) {
            return new PreToolUseResult(false, reason, null);
        }
    }
}
