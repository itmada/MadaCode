package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public class DefaultToolDisplayAdapter implements ToolDisplayAdapter {

    private final String toolName;

    public DefaultToolDisplayAdapter(String toolName) {
        this.toolName = toolName;
    }

    @Override
    public String toolName() {
        return toolName;
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(formatName(toolName), "Running...");
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        String summary = ToolDisplaySupport.completedSummary(success, durationMs);
        List<String> details = success ? List.of() : ToolDisplaySupport.firstUsefulLines(output, 3);
        return success
                ? ToolDisplay.success(formatName(toolName), summary, details)
                : ToolDisplay.failed(formatName(toolName), summary, details);
    }

    private static String formatName(String name) {
        if (name == null || name.isBlank()) {
            return "Tool";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
