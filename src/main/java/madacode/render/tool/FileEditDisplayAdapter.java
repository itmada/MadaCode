package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
        return render(input, success, output, durationMs, 3);
    }

    @Override
    public ToolDisplay renderResultVerbose(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, 50);
    }

    private ToolDisplay render(ObjectNode input, boolean success, String output, long durationMs, int maxLines) {
        String changes = ToolDisplaySupport.lineChangeSummary(output);
        String summary = success
                ? "Updated 1 file" + (changes.isBlank() ? "" : "  " + changes)
                : ToolDisplaySupport.completedSummary(false, durationMs);
        return success
                ? ToolDisplay.success(title(input), summary, java.util.List.of())
                : ToolDisplay.failed(title(input), summary, ToolDisplaySupport.firstUsefulLines(output, maxLines));
    }

    private static String title(ObjectNode input) {
        return "Edit" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "file_path"));
    }
}
