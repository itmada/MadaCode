package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class FileEditDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "edit";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "Editing...");
    }

    @Override
    public String activityDescription(ObjectNode input) {
        return "Editing " + ToolDisplaySupport.fitEnd(
                ToolDisplaySupport.text(input, "file_path"), 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, success ? 10 : 3);
    }

    @Override
    public ToolDisplay renderResultVerbose(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, success ? 200 : 50);
    }

    private ToolDisplay render(ObjectNode input, boolean success, String output, long durationMs, int maxLines) {
        String summary = success ? "Updated 1 file" : ToolDisplaySupport.completedSummary(false, durationMs);
        List<String> details = success
                ? ToolDisplaySupport.diffLines(output, maxLines)
                : ToolDisplaySupport.firstUsefulLines(output, maxLines);
        return success
                ? ToolDisplay.success(title(input), summary, details)
                : ToolDisplay.failed(title(input), summary, details);
    }

    private static String title(ObjectNode input) {
        return "Edit" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "file_path"));
    }
}
