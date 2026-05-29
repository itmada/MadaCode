package madacode.render.tool;

import madacode.util.ToolNameNormalizer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ToolDisplayRegistry {

    private final Map<String, ToolDisplayAdapter> adapters;
    private final Map<String, String> aliases;

    public ToolDisplayRegistry(List<ToolDisplayAdapter> adapters) {
        this.adapters = new HashMap<>();
        this.aliases = new HashMap<>();
        for (ToolDisplayAdapter adapter : adapters) {
            String canonical = adapter.toolName();
            this.adapters.put(canonical, adapter);
            this.aliases.put(canonical, canonical);
            String normalized = ToolNameNormalizer.normalize(canonical);
            if (normalized != null && !normalized.isBlank()) {
                this.aliases.put(normalized, canonical);
            }
            // Backward-compat alias for historical web_fetch naming.
            if ("web_fetch".equals(canonical)) {
                this.aliases.put("webfetch", canonical);
            }
        }
    }

    public static ToolDisplayRegistry defaults() {
        return new ToolDisplayRegistry(List.of(
                new BashDisplayAdapter(),
                new FileReadDisplayAdapter(),
                new FileWriteDisplayAdapter(),
                new FileEditDisplayAdapter(),
                new GrepDisplayAdapter(),
                new GlobDisplayAdapter(),
                new WebFetchDisplayAdapter(),
                new SkillDisplayAdapter("skill"),
                new AgentDisplayAdapter()));
    }

    public ToolDisplay renderStart(String toolName, ObjectNode input) {
        return adapter(toolName).renderStart(input);
    }

    public ToolDisplay renderRunning(String toolName, ObjectNode input, ToolProgressSnapshot progress) {
        return adapter(toolName).renderRunning(input, progress);
    }

    public ToolDisplay renderRunning(String toolName, ObjectNode input, List<ToolProgressLine> progressLines) {
        return renderRunning(toolName, input, ToolProgressSnapshot.of(progressLines));
    }

    public ToolDisplay renderQueued(String toolName, ObjectNode input) {
        return adapter(toolName).renderQueued(input);
    }

    public String activityDescription(String toolName, ObjectNode input) {
        return adapter(toolName).activityDescription(input);
    }

    public ToolDisplay renderResult(
            String toolName,
            ObjectNode input,
            boolean success,
            String output,
            long durationMs) {
        return adapter(toolName).renderResult(input, success, output, durationMs);
    }

    public ToolDisplay renderResultVerbose(
            String toolName,
            ObjectNode input,
            boolean success,
            String output,
            long durationMs) {
        return adapter(toolName).renderResultVerbose(input, success, output, durationMs);
    }

    public ToolDisplay renderDenied(
            String toolName,
            ObjectNode input,
            String reason,
            long durationMs) {
        return adapter(toolName).renderDenied(input, reason, durationMs);
    }

    public ToolDisplay renderError(
            String toolName,
            ObjectNode input,
            String output,
            long durationMs) {
        return adapter(toolName).renderError(input, output, durationMs);
    }

    public ToolDisplay renderSuccess(
            String toolName,
            ObjectNode input,
            String output,
            long durationMs) {
        return adapter(toolName).renderSuccess(input, output, durationMs);
    }

    private ToolDisplayAdapter adapter(String toolName) {
        String key = resolveKey(toolName);
        return adapters.computeIfAbsent(key, DefaultToolDisplayAdapter::new);
    }

    private String resolveKey(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }
        if (adapters.containsKey(toolName)) {
            return toolName;
        }
        String byAlias = aliases.get(toolName);
        if (byAlias != null) {
            return byAlias;
        }
        String normalized = ToolNameNormalizer.normalize(toolName);
        if (normalized == null || normalized.isBlank()) {
            return toolName;
        }
        return aliases.getOrDefault(normalized, normalized);
    }
}
