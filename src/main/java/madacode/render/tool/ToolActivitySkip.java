package madacode.render.tool;

import madacode.tui.theme.Tk;

import java.util.List;

/** Shared compact rendering for tool calls that did not actually complete work. */
public final class ToolActivitySkip {

    private static final String CANCELLED_BEFORE_EXECUTION = "Cancelled before execution:";
    private static final String CANCELLED = "Cancelled:";
    private static final String TOOL_CALL_SKIPPED = "Tool call skipped:";

    private ToolActivitySkip() {}

    public record Classification(String summary, List<String> detailLines) {}

    public static Classification classify(String output) {
        String line = matchedLine(output);
        if (line == null) {
            return null;
        }
        if (line.startsWith(CANCELLED_BEFORE_EXECUTION)
                && line.substring(CANCELLED_BEFORE_EXECUTION.length()).strip().equals("permission_denied")) {
            return new Classification("Skipped", List.of(Tk.dim(
                    "Tool call skipped: previous permission request was denied")));
        }
        String summary = line.startsWith(TOOL_CALL_SKIPPED) ? "Skipped" : "Cancelled";
        return new Classification(summary, List.of(Tk.dim(line)));
    }

    public static ToolDisplay compactDisplay(ToolDisplay base, String output) {
        Classification classified = classify(output);
        if (classified == null) {
            return null;
        }
        return new ToolDisplay(
                base.title(),
                classified.summary(),
                classified.detailLines(),
                classified.detailLines(),
                DisplayStatus.INFO);
    }

    private static String matchedLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        List<String> lines = output.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return null;
        }
        String first = lines.getFirst();
        if (first.startsWith(CANCELLED_BEFORE_EXECUTION) || first.startsWith(TOOL_CALL_SKIPPED)) {
            return first;
        }
        if (first.startsWith(CANCELLED)) {
            return first;
        }
        String last = lines.getLast();
        if (last.startsWith(CANCELLED)) {
            return last;
        }
        return null;
    }
}
