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
        return ToolDisplay.running(title(input), "editing");
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
                ? ToolDisplaySupport.withDuration(changes.isBlank() ? "updated" : changes, durationMs)
                : ToolDisplaySupport.completedSummary(false, durationMs);
        if (!success) {
            return ToolDisplay.failed(title(input), summary, ToolDisplaySupport.firstUsefulLines(output, maxLines));
        }
        String diff = ToolDisplaySupport.diffBlock(output);
        if (diff.isBlank()) {
            return ToolDisplay.success(title(input), summary, List.of());
        }
        List<String> details = ToolDisplaySupport.diffLines(diff, 6);
        List<String> verbose = ToolDisplaySupport.diffLines(diff, Integer.MAX_VALUE);
        return new ToolDisplay(title(input), summary, details, verbose, DisplayStatus.SUCCESS);
    }

    private static String title(ObjectNode input) {
        return "Edit" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "file_path"));
    }
}
