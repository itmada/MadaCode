package madacode.longrunning;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class LongRunningMonitorReader {

    private static final int MAX_RECENT_EVENTS = 6;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final LongRunningController.TaskStoreFactory taskStoreFactory;

    public LongRunningMonitorReader() {
        this(LongRunningTaskStore::new);
    }

    LongRunningMonitorReader(LongRunningController.TaskStoreFactory taskStoreFactory) {
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
    }

    public LongRunningMonitorSnapshot read(Path projectDir, String taskId, boolean interrupting) {
        if (taskId == null || taskId.isBlank()) {
            return unavailable("<none>", "No active task.", interrupting);
        }
        try {
            LongRunningTaskStore store = taskStoreFactory.create(projectDir);
            LongRunningTaskMetadata metadata = store.loadTask(taskId);
            List<LongRunningTaskEvent> events = store.readEvents(taskId);
            return snapshot(taskId, metadata.status(), events, interrupting);
        } catch (RuntimeException exception) {
            return unavailable(taskId, "Monitor unavailable: " + safeMessage(exception), interrupting);
        }
    }

    private LongRunningMonitorSnapshot snapshot(
            String taskId,
            String stage,
            List<LongRunningTaskEvent> events,
            boolean interrupting) {
        String workerSessionId = null;
        Integer cycle = null;
        Integer limit = null;
        String currentTarget = null;
        String currentAction = null;
        List<String> recent = new ArrayList<>();

        for (LongRunningTaskEvent event : events) {
            Map<String, String> details = event.details();
            if ("worker_started".equals(event.type())) {
                cycle = parseInt(details.get("cycle"), cycle);
                limit = parseInt(details.get("allowedCycles"), limit);
                workerSessionId = null;
                currentTarget = null;
                currentAction = "Worker cycle running...";
            } else if ("worker_finished".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
                currentAction = firstNonBlank(event.message(), currentAction);
            } else if ("worker_report".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
                currentTarget = targetFromReport(details);
                currentAction = "Report " + workerStatus(event) + ": " + safeText(event.message());
            } else if ("launcher_stopped".equals(event.type())) {
                currentAction = safeText(event.message());
            }

            eventLine(event).ifPresent(recent::add);
        }

        if (recent.size() > MAX_RECENT_EVENTS) {
            recent = recent.subList(recent.size() - MAX_RECENT_EVENTS, recent.size());
        }

        return new LongRunningMonitorSnapshot(
                taskId,
                stage,
                workerSessionId,
                cycle,
                limit,
                currentTarget,
                currentAction,
                recent,
                interrupting);
    }

    private static java.util.Optional<String> eventLine(LongRunningTaskEvent event) {
        String action = switch (event.type()) {
            case "worker_started" -> "Worker cycle " + valueOr(event.details().get("cycle"), "?") + " started";
            case "worker_finished" -> "Worker finished: " + safeText(event.message());
            case "worker_report" -> "Report " + workerStatus(event) + ": " + safeText(event.message());
            case "launcher_started" -> "Launcher started";
            case "launcher_stopped" -> "Launcher stopped: " + safeText(event.message());
            case "task_execution_started" -> "Task execution started";
            default -> null;
        };
        if (action == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(TIME_FORMAT.format(event.timestamp()) + " " + action);
    }

    private static LongRunningMonitorSnapshot unavailable(String taskId, String message, boolean interrupting) {
        return new LongRunningMonitorSnapshot(
                taskId,
                "RUNNING",
                null,
                null,
                null,
                null,
                message,
                List.of(message),
                interrupting);
    }

    private static String targetFromReport(Map<String, String> details) {
        String feature = blankToNull(details.get("featureId"));
        String issue = blankToNull(details.get("issueId"));
        if (feature != null && issue != null) {
            return feature + " · " + issue;
        }
        return firstNonBlank(feature, issue);
    }

    private static String workerStatus(LongRunningTaskEvent event) {
        String action = blankToNull(event.action());
        if (action != null) {
            return action.toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    private static Integer parseInt(String value, Integer fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? "" : normalized.replaceAll("\\s+", " ");
    }

    private static String valueOr(String value, String fallback) {
        return blankToNull(value) == null ? fallback : value.strip();
    }

    private static String firstNonBlank(String first, String second) {
        String normalized = blankToNull(first);
        return normalized == null ? blankToNull(second) : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isBlank() ? null : stripped;
    }
}
