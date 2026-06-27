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
        LongRunningMonitorActivityStatus currentActionStatus,
        List<LongRunningMonitorActivity> recentEvents,
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
        currentActionStatus = currentActionStatus == null
                ? LongRunningMonitorActivityStatus.RUNNING
                : currentActionStatus;
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

record LongRunningMonitorActivity(
        String time,
        String body,
        LongRunningMonitorActivityKind kind,
        LongRunningMonitorActivityStatus status,
        int repeatCount) {

    LongRunningMonitorActivity {
        time = blankToNull(time);
        body = blankToDefault(body, "");
        kind = kind == null ? LongRunningMonitorActivityKind.OUTPUT : kind;
        status = status == null ? LongRunningMonitorActivityStatus.NEUTRAL : status;
        repeatCount = Math.max(1, repeatCount);
    }

    LongRunningMonitorActivity withRepeatCount(int count) {
        return new LongRunningMonitorActivity(time, body, kind, status, count);
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

enum LongRunningMonitorActivityKind {
    COMMAND,
    CYCLE,
    FINISHED,
    INSPECT,
    LAUNCHER,
    OUTPUT,
    REPORT,
    TOOL,
    UPDATE
}

enum LongRunningMonitorActivityStatus {
    FAILURE,
    INFO,
    NEUTRAL,
    RUNNING,
    SUCCESS,
    WARNING
}
