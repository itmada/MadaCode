package madacode.longrunning;

import java.util.List;

public record LongRunningMonitorSnapshot(
        String taskId,
        String stage,
        String workerSessionId,
        Integer cycle,
        Integer limit,
        String currentTarget,
        String currentAction,
        List<String> recentEvents,
        long secondsSinceLastEvent,
        boolean interrupting) {

    public LongRunningMonitorSnapshot {
        taskId = blankToDefault(taskId, "<none>");
        stage = blankToDefault(stage, "RUNNING");
        workerSessionId = blankToNull(workerSessionId);
        currentTarget = blankToNull(currentTarget);
        currentAction = blankToNull(currentAction);
        recentEvents = List.copyOf(recentEvents == null ? List.of() : recentEvents);
        secondsSinceLastEvent = Math.max(0, secondsSinceLastEvent);
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isBlank() ? null : stripped;
    }
}
