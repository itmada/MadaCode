package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class FileReadDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "file_read";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "reading");
    }

    @Override
    public String activityDescription(ObjectNode input) {
        return "Reading " + ToolDisplaySupport.fitEnd(
                ToolDisplaySupport.text(input, "path"), 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        String summary = success
                ? ToolDisplaySupport.withDuration(
                        ToolDisplaySupport.plural(
                                ToolDisplaySupport.countNonBlankLines(output), "line", "lines"),
                        durationMs)
                : ToolDisplaySupport.completedSummary(false, durationMs);
        List<String> details = success ? List.of() : ToolDisplaySupport.firstUsefulLines(output, 3);
        return success
                ? ToolDisplay.success(title(input), summary, details)
                : ToolDisplay.failed(title(input), summary, details);
    }

    private static String title(ObjectNode input) {
        return "Read" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "path"));
    }
}
