package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class SkillDisplayAdapter implements ToolDisplayAdapter {

    private final String toolName;

    public SkillDisplayAdapter() {
        this("skill");
    }

    public SkillDisplayAdapter(String toolName) {
        this.toolName = toolName;
    }

    @Override
    public String toolName() {
        return toolName;
    }

    @Override
    public ToolDisplay renderStart(ObjectNode input) {
        String task = ToolDisplaySupport.text(input, "task");
        String summary = ToolDisplaySupport.firstNonBlank(task, "running");
        return ToolDisplay.running(title(input), ToolDisplaySupport.truncateMiddle(summary, 80));
    }

    @Override
    public String activityDescription(ObjectNode input) {
        String task = ToolDisplaySupport.firstNonBlank(
                ToolDisplaySupport.text(input, "task"), "Running");
        return title(input) + ": " + ToolDisplaySupport.fitEnd(task, 80);
    }

    @Override
    public ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs) {
        String summary = ToolDisplaySupport.completedSummary(success, durationMs);
        return success
                ? ToolDisplay.success(title(input), summary, List.of())
                : ToolDisplay.failed(title(input), summary, List.of());
    }

    private static String title(ObjectNode input) {
        String skill = ToolDisplaySupport.text(input, "skill");
        if (skill.isBlank()) {
            return "Skill";
        }
        return "Skill(" + skill + ")";
    }
}
