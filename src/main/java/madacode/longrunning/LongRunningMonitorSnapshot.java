package madacode.longrunning;

import java.util.List;

public record LongRunningMonitorSnapshot(
        String taskId,
        String title,
        String model,
        String stage,
        String workerSessionId,
        Integer cycle,
        Integer limit,
        long elapsedSeconds,
        int featuresPassing,
        int featuresTotal,
        int issuesBlocked,
        String currentTarget,
        String currentAction,
        List<String> recentEvents,
        long secondsSinceLastEvent,
        boolean interrupting) {

    public LongRunningMonitorSnapshot {
        taskId = blankToDefault(taskId, "<none>");
        title = blankToNull(title);
        model = blankToNull(model);
        stage = blankToDefault(stage, "RUNNING");
        workerSessionId = blankToNull(workerSessionId);
        elapsedSeconds = Math.max(0, elapsedSeconds);
        featuresPassing = Math.max(0, featuresPassing);
        featuresTotal = Math.max(0, featuresTotal);
        issuesBlocked = Math.max(0, issuesBlocked);
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
