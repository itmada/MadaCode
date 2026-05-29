package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public final class BashDisplayAdapter implements ToolDisplayAdapter {

    @Override
    public String toolName() {
        return "bash";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        String command = ToolDisplaySupport.text(input, "command");
        return ToolDisplay.running("Bash" + ToolDisplaySupport.parens(command), "Running...");
    }

    @Override
    public ToolDisplay renderRunning(ObjectNode input, ToolProgressSnapshot progress) {
        String command = ToolDisplaySupport.text(input, "command");
        List<ToolProgressLine> outputs = progress.lines().stream()
                .filter(l -> l.kind() == ToolProgressLine.Kind.OUTPUT)
                .toList();
        if (outputs.isEmpty()) {
            return ToolDisplay.running("Bash" + ToolDisplaySupport.parens(command), "Running...");
        }
        int total = outputs.size();
        int start = Math.max(0, total - 10);
        int hidden = progress.droppedLineCount() + start;
        List<String> details = new ArrayList<>();
        if (hidden > 0) {
            details.add("… (" + hidden + " earlier line" + (hidden == 1 ? "" : "s") + " hidden)");
        }
        for (int i = start; i < total; i++) {
            details.add(ToolDisplaySupport.fitEnd(outputs.get(i).text(), 96));
        }
        return new ToolDisplay(
                "Bash" + ToolDisplaySupport.parens(command),
                "Running...",
                details,
                details,
                DisplayStatus.RUNNING);
    }

    @Override
    public String activityDescription(ObjectNode input) {
        String summary = ToolDisplaySupport.firstNonBlank(
                ToolDisplaySupport.text(input, "description"),
                ToolDisplaySupport.text(input, "command"));
        summary = ToolDisplaySupport.firstNonBlank(summary, "command");
        return "Running " + ToolDisplaySupport.fitEnd(summary, 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, success ? 0 : 4);
    }

    @Override
    public ToolDisplay renderResultVerbose(ObjectNode input, boolean success, String output, long durationMs) {
        return render(input, success, output, durationMs, 100);
    }

    private ToolDisplay render(ObjectNode input, boolean success, String output, long durationMs, int maxLines) {
        String command = ToolDisplaySupport.text(input, "command");
        String summary = ToolDisplaySupport.bashSummary(output, success, durationMs);
        List<String> details = success ? List.of() : ToolDisplaySupport.firstUsefulLines(output, maxLines);
        return success
                ? ToolDisplay.success("Bash" + ToolDisplaySupport.parens(command), summary, details)
                : ToolDisplay.failed("Bash" + ToolDisplaySupport.parens(command), summary, details);
    }
}
