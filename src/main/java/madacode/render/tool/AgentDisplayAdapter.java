package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public final class AgentDisplayAdapter implements ToolDisplayAdapter {

    private static final int MAX_ACTIVITY_LINES = 4;

    @Override
    public String toolName() {
        return "agent";
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        String summary = ToolDisplaySupport.firstNonBlank(
                ToolDisplaySupport.text(input, "description"),
                ToolDisplaySupport.firstNonBlank(
                        ToolDisplaySupport.text(input, "task"),
                        ToolDisplaySupport.text(input, "prompt")));
        summary = ToolDisplaySupport.firstNonBlank(summary, "Running...");
        return ToolDisplay.running(title(input), ToolDisplaySupport.truncateMiddle(summary, 80));
    }

    @Override
    public ToolDisplay renderRunning(ObjectNode input, ToolProgressSnapshot progress) {
        String summary = ToolDisplaySupport.firstNonBlank(
                ToolDisplaySupport.text(input, "description"),
                ToolDisplaySupport.firstNonBlank(
                        ToolDisplaySupport.text(input, "task"),
                        ToolDisplaySupport.text(input, "prompt")));
        summary = ToolDisplaySupport.firstNonBlank(summary, "Running...");

        List<String> activities = progress.lines().stream()
                .filter(line -> line.kind() == ToolProgressLine.Kind.ACTIVITY)
                .map(ToolProgressLine::text)
                .filter(text -> !text.isBlank())
                .toList();
        int totalActivityCount = progress.droppedActivityCount() + activities.size();
        if (totalActivityCount == 0) {
            return ToolDisplay.running(title(input), ToolDisplaySupport.truncateMiddle(summary, 80));
        }

        List<String> details = new ArrayList<>();
        details.add(ToolDisplaySupport.plural(totalActivityCount, "tool use", "tool uses"));
        int hidden = progress.droppedActivityCount() + Math.max(0, activities.size() - MAX_ACTIVITY_LINES);
        if (hidden > 0) {
            details.add("… (" + hidden + " earlier activit" + (hidden == 1 ? "y" : "ies") + " hidden)");
        }
        int start = Math.max(0, activities.size() - MAX_ACTIVITY_LINES);
        for (int i = start; i < activities.size(); i++) {
            details.add(ToolDisplaySupport.fitEnd(activities.get(i), 96));
        }
        return new ToolDisplay(
                title(input),
                ToolDisplaySupport.truncateMiddle(summary, 80),
                details,
                details,
                DisplayStatus.RUNNING);
    }

    @Override
    public String activityDescription(ObjectNode input) {
        String summary = ToolDisplaySupport.firstNonBlank(
                ToolDisplaySupport.text(input, "description"),
                ToolDisplaySupport.firstNonBlank(
                        ToolDisplaySupport.text(input, "task"),
                        ToolDisplaySupport.text(input, "prompt")));
        return title(input) + ": " + ToolDisplaySupport.fitEnd(
                ToolDisplaySupport.firstNonBlank(summary, "Running"), 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        String summary = ToolDisplaySupport.completedSummary(success, durationMs);
        return success
                ? ToolDisplay.success(title(input), summary, List.of())
                : ToolDisplay.failed(title(input), summary, List.of());
    }

    private static String title(ObjectNode input) {
        String agentType = ToolDisplaySupport.firstNonBlank(
                ToolDisplaySupport.text(input, "subagent_type"),
                ToolDisplaySupport.firstNonBlank(
                        ToolDisplaySupport.text(input, "agentType"),
                        "explorer"));
        return "Agent(" + agentType + ")";
    }
}
