package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

public final class FileWriteDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "write";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "writing");
    }

    @Override
    public String activityDescription(ObjectNode input) {
        return "Writing " + ToolDisplaySupport.fitEnd(
                ToolDisplaySupport.text(input, "file_path"), 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, 3);
    }

    @Override
    public ToolDisplay renderResultVerbose(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, 200);
    }

    private ToolDisplay render(ObjectNode input, boolean success, String output, long durationMs, int maxLines) {
        String content = ToolDisplaySupport.text(input, "content");
        String summary = success
                ? "Wrote " + ToolDisplaySupport.plural(ToolDisplaySupport.countNonBlankLines(content), "line", "lines")
                : ToolDisplaySupport.completedSummary(false, durationMs);
        return success
                ? ToolDisplay.success(title(input), summary, java.util.List.of())
                : ToolDisplay.failed(title(input), summary, ToolDisplaySupport.firstUsefulLines(output, maxLines));
    }

    private static String title(ObjectNode input) {
        return "Write" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "file_path"));
    }
}
