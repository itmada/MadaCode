package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class GlobDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "glob";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "listing");
    }

    @Override
    public String activityDescription(ObjectNode input) {
        return "Finding " + ToolDisplaySupport.quote(ToolDisplaySupport.text(input, "pattern"));
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        int files = ToolDisplaySupport.countNonBlankLines(output);
        String summary = success
                ? ToolDisplaySupport.withDuration(
                        ToolDisplaySupport.plural(files, "file", "files"), durationMs)
                : ToolDisplaySupport.completedSummary(false, durationMs);
        List<String> details = success ? List.of() : ToolDisplaySupport.firstUsefulLines(output, 3);
        return success
                ? ToolDisplay.success(title(input), summary, details)
                : ToolDisplay.failed(title(input), summary, details);
    }

    private static String title(ObjectNode input) {
        return "List(" + ToolDisplaySupport.quote(ToolDisplaySupport.text(input, "pattern")) + ")";
    }
}
