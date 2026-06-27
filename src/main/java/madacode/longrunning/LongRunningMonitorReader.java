package madacode.longrunning;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
        return read(projectDir, taskId, null, interrupting);
    }

    public LongRunningMonitorSnapshot read(Path projectDir, String taskId, String model, boolean interrupting) {
        if (taskId == null || taskId.isBlank()) {
            return unavailable("<none>", "No active task.", interrupting);
        }
        try {
            LongRunningTaskStore store = taskStoreFactory.create(projectDir);
            LongRunningTaskMetadata metadata = store.loadTask(taskId);
            List<LongRunningTaskEvent> events = store.readRecentEvents(taskId, 500);
            List<FeatureItem> features = store.readFeatureList(taskId);
            List<KnownIssue> issues = store.readKnownIssues(taskId);
            return snapshot(taskId, metadata, model, events, features, issues,
                    defaultTarget(features, issues), interrupting);
        } catch (RuntimeException exception) {
            return unavailable(taskId, "Monitor unavailable: " + safeMessage(exception), interrupting);
        }
    }

    private LongRunningMonitorSnapshot snapshot(
            String taskId,
            LongRunningTaskMetadata metadata,
            String model,
            List<LongRunningTaskEvent> events,
            List<FeatureItem> features,
            List<KnownIssue> issues,
            String defaultTarget,
            boolean interrupting) {
        String stage = metadata.status();
        String workerSessionId = null;
        Integer cycle = null;
        Integer limit = null;
        String currentTarget = defaultTarget;
        String currentAction = null;
        LongRunningMonitorActivityStatus currentActionStatus = LongRunningMonitorActivityStatus.RUNNING;
        List<LongRunningMonitorActivity> recent = new ArrayList<>();
        boolean lastRecentWasInspect = false;
        Instant lastEventTime = null;

        for (LongRunningTaskEvent event : events) {
            lastEventTime = event.timestamp();
            Map<String, String> details = event.details();
            if ("worker_started".equals(event.type())) {
                cycle = parseInt(details.get("cycle"), cycle);
                limit = parseInt(details.get("allowedCycles"), limit);
                workerSessionId = null;
                currentTarget = defaultTarget;
                currentAction = "Worker cycle running...";
                currentActionStatus = LongRunningMonitorActivityStatus.RUNNING;
            } else if ("worker_finished".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
                currentAction = firstNonBlank(event.message(), currentAction);
                currentActionStatus = statusFromEvent(event);
            } else if ("worker_report".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
                currentTarget = firstNonBlank(targetFromReport(details), defaultTarget);
                currentAction = "Report " + workerStatus(event) + ": " + safeText(event.message());
                currentActionStatus = reportStatus(event);
            } else if ("launcher_stopped".equals(event.type())) {
                currentAction = safeText(event.message());
                currentActionStatus = statusFromEvent(event);
            } else if ("task_update".equals(event.type())) {
                currentTarget = firstNonBlank(targetFromReport(details), currentTarget);
            } else if ("worker_tool_started".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
                currentAction = currentActionFromTool(event);
                currentActionStatus = LongRunningMonitorActivityStatus.RUNNING;
            } else if ("worker_tool_output".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
            } else if ("worker_tool_result".equals(event.type())
                    || "worker_tool_completed".equals(event.type())) {
                workerSessionId = firstNonBlank(details.get("workerSessionId"), workerSessionId);
                if (Boolean.FALSE.equals(event.success())) {
                    currentAction = toolResultEventLine(event);
                    currentActionStatus = statusFromEvent(event);
                }
            }

            java.util.Optional<LongRunningMonitorActivity> activity = eventActivity(event);
            if (activity.isPresent()) {
                boolean inspect = "inspect".equals(event.action());
                if (!(inspect && lastRecentWasInspect)) {
                    recent.add(activity.get());
                }
                lastRecentWasInspect = inspect;
            }
        }

        recent = foldConsecutive(recent);
        if (recent.size() > MAX_RECENT_EVENTS) {
            recent = recent.subList(recent.size() - MAX_RECENT_EVENTS, recent.size());
        }

        int featuresTotal = features.size();
        int featuresPassing = (int) features.stream().filter(FeatureItem::passes).count();
        int issuesBlocked = (int) issues.stream()
                .filter(issue -> "open".equals(issue.status()) || "blocked".equals(issue.status()))
                .count();

        return new LongRunningMonitorSnapshot(
                taskId,
                metadata.title(),
                model,
                stage,
                workerSessionId,
                cycle,
                limit,
                elapsedSeconds(metadata),
                featuresPassing,
                featuresTotal,
                issuesBlocked,
                currentTarget,
                currentAction,
                currentActionStatus,
                recent,
                secondsSince(lastEventTime),
                interrupting);
    }

    private static long elapsedSeconds(LongRunningTaskMetadata metadata) {
        Instant started = metadata.executionStarted();
        if (started == null) {
            return 0;
        }
        return Math.max(0, Duration.between(started, Instant.now()).toSeconds());
    }

    /**
     * Collapse consecutive events whose display body is identical into a single
     * line, keeping the latest timestamp and appending a {@code ×N} repeat
     * count. Prevents a retrying worker from flooding the monitor with the same
     * line while preserving the event status/kind for rendering.
     */
    private static List<LongRunningMonitorActivity> foldConsecutive(List<LongRunningMonitorActivity> activities) {
        List<LongRunningMonitorActivity> folded = new ArrayList<>();
        LongRunningMonitorActivity last = null;
        int count = 0;
        for (LongRunningMonitorActivity activity : activities) {
            if (last != null
                    && activity.body().equals(last.body())
                    && activity.kind() == last.kind()
                    && activity.status() == last.status()) {
                count++;
                folded.set(folded.size() - 1, activity.withRepeatCount(count));
            } else {
                folded.add(activity);
                last = activity;
                count = 1;
            }
        }
        return folded;
    }

    private static java.util.Optional<LongRunningMonitorActivity> eventActivity(LongRunningTaskEvent event) {
        LongRunningMonitorActivityKind kind = switch (event.type()) {
            case "worker_started" -> LongRunningMonitorActivityKind.CYCLE;
            case "worker_finished" -> LongRunningMonitorActivityKind.FINISHED;
            case "worker_report" -> LongRunningMonitorActivityKind.REPORT;
            case "worker_tool_started" -> toolStartedKind(event);
            case "worker_tool_output" -> LongRunningMonitorActivityKind.OUTPUT;
            case "worker_tool_result", "worker_tool_completed" -> LongRunningMonitorActivityKind.COMMAND;
            case "launcher_started", "launcher_stopped", "task_execution_started" -> LongRunningMonitorActivityKind.LAUNCHER;
            default -> null;
        };
        if (kind == null) {
            return java.util.Optional.empty();
        }
        String body = switch (event.type()) {
            case "worker_started" -> "Worker cycle " + valueOr(event.details().get("cycle"), "?") + " started";
            case "worker_finished" -> "Worker finished: " + safeText(event.message());
            case "worker_report" -> "Report " + workerStatus(event) + ": " + safeText(event.message());
            case "worker_tool_started" -> toolStartedEventLine(event);
            case "worker_tool_output" -> toolOutputEventLine(event);
            case "worker_tool_result", "worker_tool_completed" -> toolResultEventLine(event);
            case "launcher_started" -> "Launcher started";
            case "launcher_stopped" -> "Launcher stopped: " + safeText(event.message());
            case "task_execution_started" -> "Task execution started";
            default -> null;
        };
        if (body == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new LongRunningMonitorActivity(
                TIME_FORMAT.format(event.timestamp()),
                body,
                kind,
                activityStatus(event),
                1));
    }

    private static String currentActionFromTool(LongRunningTaskEvent event) {
        String action = event.action();
        String message = safeText(event.message());
        return switch (action == null ? "" : action) {
            case "bash" -> "正在运行: " + stripVerb(message, "Run ");
            case "edit" -> message;
            case "inspect" -> "Inspecting project files";
            case "task_update" -> "Updating task store";
            default -> message.isBlank() ? "Working..." : message;
        };
    }

    private static String toolStartedEventLine(LongRunningTaskEvent event) {
        String action = event.action();
        String message = safeText(event.message());
        return switch (action == null ? "" : action) {
            case "inspect" -> "Inspect project files";
            case "bash" -> "Run " + stripVerb(message, "Run ");
            case "edit" -> message;
            case "task_update" -> "Update task store";
            default -> message.isBlank() ? null : message;
        };
    }

    private static LongRunningMonitorActivityKind toolStartedKind(LongRunningTaskEvent event) {
        return switch (event.action() == null ? "" : event.action()) {
            case "bash", "edit" -> LongRunningMonitorActivityKind.COMMAND;
            case "inspect" -> LongRunningMonitorActivityKind.INSPECT;
            case "task_update" -> LongRunningMonitorActivityKind.UPDATE;
            default -> LongRunningMonitorActivityKind.TOOL;
        };
    }

    private static LongRunningMonitorActivityStatus activityStatus(LongRunningTaskEvent event) {
        if ("worker_report".equals(event.type())) {
            return reportStatus(event);
        }
        if ("worker_tool_output".equals(event.type())) {
            return LongRunningMonitorActivityStatus.NEUTRAL;
        }
        if ("worker_started".equals(event.type())
                || "worker_tool_started".equals(event.type())
                || "launcher_started".equals(event.type())
                || "task_execution_started".equals(event.type())) {
            return LongRunningMonitorActivityStatus.RUNNING;
        }
        return statusFromEvent(event);
    }

    private static LongRunningMonitorActivityStatus statusFromEvent(LongRunningTaskEvent event) {
        if (Boolean.FALSE.equals(event.success())) {
            return LongRunningMonitorActivityStatus.FAILURE;
        }
        if (Boolean.TRUE.equals(event.success())) {
            return LongRunningMonitorActivityStatus.SUCCESS;
        }
        return LongRunningMonitorActivityStatus.NEUTRAL;
    }

    private static LongRunningMonitorActivityStatus reportStatus(LongRunningTaskEvent event) {
        if (Boolean.FALSE.equals(event.success())) {
            return LongRunningMonitorActivityStatus.FAILURE;
        }
        return switch (workerStatus(event)) {
            case "progress_made", "task_completed" -> LongRunningMonitorActivityStatus.SUCCESS;
            case "blocked" -> LongRunningMonitorActivityStatus.WARNING;
            case "needs_user" -> LongRunningMonitorActivityStatus.INFO;
            case "failed" -> LongRunningMonitorActivityStatus.FAILURE;
            default -> statusFromEvent(event);
        };
    }

    private static String toolOutputEventLine(LongRunningTaskEvent event) {
        String message = safeText(event.message());
        return message.isBlank() ? null : message;
    }

    private static String toolResultEventLine(LongRunningTaskEvent event) {
        if (!Boolean.FALSE.equals(event.success())) {
            return null;
        }
        String message = safeText(event.message());
        if (message.isBlank()) {
            return null;
        }
        return "bash".equals(event.action()) ? stripVerb(message, "Run ") : message;
    }

    private static LongRunningMonitorSnapshot unavailable(String taskId, String message, boolean interrupting) {
        return new LongRunningMonitorSnapshot(
                taskId,
                null,
                null,
                "RUNNING",
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                null,
                message,
                LongRunningMonitorActivityStatus.FAILURE,
                List.of(new LongRunningMonitorActivity(
                        null,
                        message,
                        LongRunningMonitorActivityKind.LAUNCHER,
                        LongRunningMonitorActivityStatus.FAILURE,
                        1)),
                0,
                interrupting);
    }

    private static long secondsSince(Instant timestamp) {
        if (timestamp == null) {
            return 0;
        }
        long seconds = Duration.between(timestamp, Instant.now()).toSeconds();
        return Math.max(0, seconds);
    }

    private static String defaultTarget(List<FeatureItem> features, List<KnownIssue> issues) {
        String activeIssue = issues.stream()
                .filter(issue -> "open".equals(issue.status()) || "blocked".equals(issue.status()))
                .findFirst()
                .map(issue -> "Issue " + issue.id() + " " + issue.description())
                .orElse(null);
        if (activeIssue != null) {
            return fit(activeIssue, 80);
        }
        return features.stream()
                .filter(feature -> !feature.passes())
                .findFirst()
                .map(feature -> fit(feature.id() + " " + feature.description(), 80))
                .orElse(null);
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

    private static String stripVerb(String value, String prefix) {
        String normalized = safeText(value);
        return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
    }

    private static String fit(String value, int maxLength) {
        String text = safeText(value);
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
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
