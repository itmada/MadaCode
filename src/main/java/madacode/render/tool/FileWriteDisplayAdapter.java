package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class FileWriteDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "write";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "Writing...");
    }

    @Override
    public String activityDescription(ObjectNode input) {
        return "Writing " + ToolDisplaySupport.fitEnd(
                ToolDisplaySupport.text(input, "file_path"), 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, success ? 8 : 3);
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
        List<String> details = success
                ? ToolDisplaySupport.diffLines(output, maxLines)
                : ToolDisplaySupport.firstUsefulLines(output, maxLines);
        return success
                ? ToolDisplay.success(title(input), summary, details)
                : ToolDisplay.failed(title(input), summary, details);
    }

    private static String title(ObjectNode input) {
        return "Write" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "file_path"));
    }
}
