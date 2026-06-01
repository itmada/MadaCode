package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

public final class GrepDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "grep";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "searching");
    }

    @Override
    public ToolDisplay renderRunning(ObjectNode input, ToolProgressSnapshot progress) {
        Optional<ToolProgressLine> latest = progress.lines().stream()
                .filter(l -> l.kind() == ToolProgressLine.Kind.METRIC)
                .reduce((a, b) -> b);
        String summary = latest.map(ToolProgressLine::text).orElse("searching");
        return ToolDisplay.running(title(input), summary);
    }

    @Override
    public String activityDescription(ObjectNode input) {
        String pattern = ToolDisplaySupport.text(input, "pattern");
        String path = ToolDisplaySupport.text(input, "path");
        String base = "Searching for " + ToolDisplaySupport.quote(pattern);
        if (!path.isBlank()) {
            base += " in " + ToolDisplaySupport.fitEnd(path, 40);
        }
        return base;
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        int files = ToolDisplaySupport.countNonBlankLines(output);
        String summary = success
                ? "Found " + ToolDisplaySupport.plural(files, "file", "files")
                : ToolDisplaySupport.completedSummary(false, durationMs);
        List<String> details = success ? List.of() : ToolDisplaySupport.firstUsefulLines(output, 3);
        return success
                ? ToolDisplay.success(title(input), summary, details)
                : ToolDisplay.failed(title(input), summary, details);
    }

    private static String title(ObjectNode input) {
        String pattern = ToolDisplaySupport.text(input, "pattern");
        String path = ToolDisplaySupport.text(input, "path");
        String suffix = path.isBlank() ? "" : " in " + ToolDisplaySupport.truncateMiddle(path, 36);
        return "Search(" + ToolDisplaySupport.quote(pattern) + suffix + ")";
    }
}
