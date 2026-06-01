package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebFetchDisplayAdapter implements ToolDisplayAdapter {

    private static final Pattern STATUS = Pattern.compile("Status:\\s*(\\d+)");
    private static final Pattern SIZE = Pattern.compile("Size:\\s*([^\\n]+)");

    @Override
    public String toolName() {
        return "web_fetch";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        return ToolDisplay.running(title(input), "fetching");
    }

    @Override
    public ToolDisplay renderRunning(ObjectNode input, ToolProgressSnapshot progress) {
        Optional<ToolProgressLine> latest = progress.lines().stream()
                .filter(l -> l.kind() == ToolProgressLine.Kind.METRIC)
                .reduce((a, b) -> b);
        String summary = latest.map(ToolProgressLine::text).orElse("fetching");
        return ToolDisplay.running(title(input), summary);
    }

    @Override
    public String activityDescription(ObjectNode input) {
        return "Fetching " + ToolDisplaySupport.fitEnd(
                ToolDisplaySupport.text(input, "url"), 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        String summary = success ? webSummary(output, durationMs) : ToolDisplaySupport.completedSummary(false, durationMs);
        List<String> details = success ? List.of() : ToolDisplaySupport.firstUsefulLines(output, 3);
        return success
                ? ToolDisplay.success(title(input), summary, details)
                : ToolDisplay.failed(title(input), summary, details);
    }

    private static String title(ObjectNode input) {
        return "Fetch" + ToolDisplaySupport.parens(ToolDisplaySupport.text(input, "url"));
    }

    private static String webSummary(String output, long durationMs) {
        String status = match(STATUS, output);
        String size = match(SIZE, output);
        String timing = ToolDisplaySupport.duration(durationMs);
        StringBuilder summary = new StringBuilder();
        summary.append(status.isBlank() ? "Fetched" : "HTTP " + status);
        if (!size.isBlank()) {
            summary.append(" · ").append(size.strip());
        }
        if (!timing.isBlank()) {
            summary.append(" · ").append(timing);
        }
        return summary.toString();
    }

    private static String match(Pattern pattern, String output) {
        Matcher matcher = pattern.matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1) : "";
    }
}
