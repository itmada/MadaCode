package madacode.longrunning;

import madacode.core.model.FinishReason;
import madacode.core.turn.TurnResult;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates long-running task execution by launching worker agents.
 *
 * <p>The launcher is a system-level component — it is not a tool and is not
 * called by the model. It runs bounded worker cycles and decides whether to
 * continue or stop based on the worker's report.
 */
public final class LongRunningLauncher {

    private final LongRunningWorkerRunner workerRunner;
    private final LongRunningController.TaskStoreFactory taskStoreFactory;

    public LongRunningLauncher(
            LongRunningWorkerRunner workerRunner,
            LongRunningController.TaskStoreFactory taskStoreFactory) {
        this.workerRunner = Objects.requireNonNull(workerRunner, "workerRunner");
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
    }

    public LongRunningLauncher(LongRunningWorkerRunner workerRunner) {
        this(workerRunner, LongRunningTaskStore::new);
    }

    /**
     * Runs the launcher for the given task, launching up to {@code maxWorkers}
     * worker cycles.
     *
     * @param taskId         the task to execute
     * @param projectDir     the project working directory
     * @param controlSession the control session (for event recording)
     * @param maxWorkers     maximum number of worker cycles
     * @return the launch result
     */
    public LaunchResult run(String taskId, Path projectDir,
                            madacode.core.session.ConversationSession controlSession,
                            int maxWorkers) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(controlSession, "controlSession");

        LongRunningTaskStore store = taskStoreFactory.create(projectDir);
        try (LongRunningTaskLease ignored = store.acquireExecutionLease(taskId)) {
            return runWithLease(taskId, projectDir, controlSession, maxWorkers, store);
        } catch (LongRunningTaskLeaseUnavailableException exception) {
            return new LaunchResult(LaunchStatus.ALREADY_RUNNING, 0, exception.getMessage());
        }
    }

    private LaunchResult runWithLease(
            String taskId,
            Path projectDir,
            madacode.core.session.ConversationSession controlSession,
            int maxWorkers,
            LongRunningTaskStore store) {
        // Append launcher started event
        appendLauncherEvent(store, taskId, controlSession, "launcher_started",
                true, "Launcher started for task " + taskId,
                Map.of("maxWorkers", String.valueOf(maxWorkers)));

        LongRunningTaskMetadata initialMeta = store.loadTask(taskId);
        if ("DONE".equals(initialMeta.status())) {
            appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                    true, "Task is already " + initialMeta.status(),
                    Map.of("reason", "terminal_status"));
            return new LaunchResult(LaunchStatus.COMPLETED, 0, "Task already " + initialMeta.status());
        }

        try {
            LongRunningTaskMetadata executing = store.markTaskExecuting(taskId);
            appendLauncherEvent(store, taskId, controlSession, "task_execution_started",
                    true, "Task entered execution state.",
                    Map.of("status", executing.status()));
        } catch (RuntimeException e) {
            appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                    false, "Launcher could not start execution: " + e.getMessage(),
                    Map.of("reason", "execution_start_failed"));
            var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, 0, "execution_start_failed");
            if (stopFailure != null) return stopFailure;
            return new LaunchResult(LaunchStatus.FAILED, 0,
                    "Could not start long-running execution: " + e.getMessage());
        }

        for (int i = 0; i < maxWorkers; i++) {
            if (Thread.currentThread().isInterrupted()) {
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        true, "Launcher interrupted before starting next worker.",
                        Map.of("reason", "interrupted"));
                var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i, "user_interrupted");
                if (stopFailure != null) return stopFailure;
                return new LaunchResult(LaunchStatus.INTERRUPTED, i,
                        "Launcher interrupted before starting next worker.");
            }

            int allowedCycles = allowedWorkerCycles(store, taskId, maxWorkers);
            if (i >= allowedCycles) {
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        true, "Launcher exhausted dynamic worker cycle budget",
                        Map.of(
                                "reason", "worker_cycle_budget_exhausted",
                                "allowedCycles", String.valueOf(allowedCycles),
                                "hardLimit", String.valueOf(maxWorkers)));
                var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i, "worker_cycle_budget_exhausted");
                if (stopFailure != null) return stopFailure;
                return new LaunchResult(LaunchStatus.MAX_WORKERS_EXHAUSTED, i,
                        "Launcher exhausted " + allowedCycles
                                + " worker cycle(s) allowed by the current feature and issue lists.");
            }

            // Check if task is already terminal
            LongRunningTaskMetadata meta = store.loadTask(taskId);
            if ("DONE".equals(meta.status())) {
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        true, "Task is already " + meta.status(),
                        Map.of("reason", "terminal_status"));
                return new LaunchResult(LaunchStatus.COMPLETED, i, "Task already " + meta.status());
            }

            // Append worker started event
            appendLauncherEvent(store, taskId, controlSession, "worker_started",
                    true, "Starting worker cycle " + (i + 1) + " of " + allowedCycles,
                    Map.of(
                            "cycle", String.valueOf(i + 1),
                            "allowedCycles", String.valueOf(allowedCycles),
                            "hardLimit", String.valueOf(maxWorkers)));

            // Run the worker
            LongRunningWorkerRunner.WorkerRunResult result;
            try {
                result = workerRunner.run(taskId, projectDir);
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted() || causedByInterruption(e)) {
                    appendLauncherEvent(store, taskId, controlSession, "worker_finished",
                            false, "Worker interrupted: " + safeMessage(e),
                            Map.of("cycle", String.valueOf(i + 1), "reason", "interrupted"));
                    appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                            true, "Launcher interrupted during worker cycle.",
                            Map.of("reason", "interrupted"));
                    var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, "user_interrupted");
                    if (stopFailure != null) return stopFailure;
                    return new LaunchResult(LaunchStatus.INTERRUPTED, i + 1,
                            "Launcher interrupted during worker cycle.");
                }
                appendLauncherEvent(store, taskId, controlSession, "worker_finished",
                        false, "Worker crashed: " + e.getMessage(),
                        Map.of("cycle", String.valueOf(i + 1), "error", e.getMessage()));
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        false, "Launcher stopped due to worker crash",
                        Map.of("reason", "worker_crash"));
                var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, "worker_crash");
                if (stopFailure != null) return stopFailure;
                return new LaunchResult(LaunchStatus.FAILED, i + 1,
                        "Worker crashed: " + e.getMessage());
            }

            // Check if worker produced a report
            if (result.report().isEmpty()) {
                WorkerNoReportCause cause = WorkerNoReportCause.from(result.turnResult());
                appendLauncherEvent(store, taskId, controlSession, "worker_finished",
                        false, cause.workerMessage(),
                        cause.workerDetails(i + 1, result.workerSessionId()));
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        false, cause.launcherMessage(),
                        cause.launcherDetails());
                var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, cause.reason());
                if (stopFailure != null) return stopFailure;
                return new LaunchResult(LaunchStatus.FAILED, i + 1,
                        cause.resultMessage());
            }

            WorkerReport report = result.report().get();
            appendLauncherEvent(store, taskId, controlSession, "worker_finished",
                    true, "Worker completed: " + report.summary(),
                    Map.of(
                            "cycle", String.valueOf(i + 1),
                            "status", report.status().name().toLowerCase(Locale.ROOT),
                            "workerSessionId", result.workerSessionId()));

            // Decide based on report status
            switch (report.status()) {
                case PROGRESS_MADE -> {
                    // Continue to next worker cycle
                    continue;
                }
                case TASK_COMPLETED -> {
                    // Try to mark task completed
                    try {
                        store.markTaskCompleted(taskId);
                        appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                                true, "Task completed successfully",
                                Map.of("reason", "task_completed"));
                        return new LaunchResult(LaunchStatus.COMPLETED, i + 1,
                                "Task completed: " + report.summary());
                    } catch (RuntimeException e) {
                        appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                                false, "Task completion preconditions not met: " + e.getMessage(),
                                Map.of("reason", "completion_failed"));
                        var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, "completion_failed");
                        if (stopFailure != null) return stopFailure;
                        return new LaunchResult(LaunchStatus.NEEDS_USER, i + 1,
                                "Worker reported task_completed but preconditions not met: " + e.getMessage());
                    }
                }
                case BLOCKED -> {
                    var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, "worker_blocked");
                    if (stopFailure != null) return stopFailure;
                    appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                            false, "Worker blocked: " + report.summary(),
                            Map.of("reason", "blocked"));
                    return new LaunchResult(LaunchStatus.BLOCKED, i + 1,
                            "Worker blocked: " + report.summary());
                }
                case FAILED -> {
                    var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, "worker_failed");
                    if (stopFailure != null) return stopFailure;
                    appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                            false, "Worker failed: " + report.summary(),
                            Map.of("reason", "worker_failed"));
                    return new LaunchResult(LaunchStatus.FAILED, i + 1,
                            "Worker failed: " + report.summary());
                }
                case NEEDS_USER -> {
                    var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, i + 1, "needs_user");
                    if (stopFailure != null) return stopFailure;
                    appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                            true, "Worker needs user input: " + report.summary(),
                            Map.of("reason", "needs_user"));
                    return new LaunchResult(LaunchStatus.NEEDS_USER, i + 1,
                            "Worker needs user input: " + report.summary());
                }
            }
        }

        // Exhausted max workers
        appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                true, "Launcher exhausted " + maxWorkers + " worker cycles",
                Map.of("reason", "worker_cycle_budget_exhausted"));
        var stopFailure = returnTaskToInterrupt(store, taskId, controlSession, maxWorkers, "worker_cycle_budget_exhausted");
        if (stopFailure != null) return stopFailure;
        return new LaunchResult(LaunchStatus.MAX_WORKERS_EXHAUSTED, maxWorkers,
                "Launcher exhausted " + maxWorkers + " worker cycles. Task may still have remaining work.");
    }

    private static boolean causedByInterruption(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InterruptedException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private int allowedWorkerCycles(LongRunningTaskStore store, String taskId, int hardLimit) {
        int featureCount = store.readFeatureList(taskId).size();
        int issueCount = store.readKnownIssues(taskId).size();
        return Math.min(hardLimit, Math.max(1, featureCount + issueCount));
    }

    private LaunchResult returnTaskToInterrupt(
            LongRunningTaskStore store,
            String taskId,
            madacode.core.session.ConversationSession controlSession,
            int workersLaunched,
            String reason) {
        try {
            store.markTaskInterrupted(taskId, reason);
            return null;
        } catch (RuntimeException exception) {
            appendLauncherEvent(store, taskId, controlSession, "launcher_stop_state_update_failed",
                    false, "Failed to mark task INTERRUPT: " + exception.getMessage(),
                    Map.of("reason", reason));
            return new LaunchResult(LaunchStatus.FAILED, workersLaunched,
                    "Launcher stopped but could not mark task INTERRUPT: " + exception.getMessage());
        }
    }

    private void appendLauncherEvent(
            LongRunningTaskStore store,
            String taskId,
            madacode.core.session.ConversationSession controlSession,
            String type,
            boolean success,
            String message,
            Map<String, String> details) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    type,
                    taskId,
                    controlSession.sessionId(),
                    controlSession.longRunningStage() == null
                            ? null : controlSession.longRunningStage().name(),
                    null,
                    success,
                    message,
                    details));
        } catch (RuntimeException ignored) {
            // Event logging is diagnostic.
        }
    }

    /**
     * Status of a launcher run.
     */
    public enum LaunchStatus {
        COMPLETED,
        ALREADY_RUNNING,
        BLOCKED,
        FAILED,
        NEEDS_USER,
        INTERRUPTED,
        MAX_WORKERS_EXHAUSTED
    }

    /**
     * Result of a launcher run.
     */
    public record LaunchResult(
            LaunchStatus status,
            int workersLaunched,
            String message
    ) {}

    private record WorkerNoReportCause(
            String reason,
            String workerMessage,
            String launcherMessage,
            String resultMessage,
            FinishReason finishReason,
            String finalText) {

        static WorkerNoReportCause from(TurnResult turnResult) {
            FinishReason finishReason = turnResult == null ? null : turnResult.finishReason();
            String finalText = sanitizeFinalText(turnResult == null ? null : turnResult.finalText());
            String suffix = finalText == null || finalText.isBlank()
                    ? ""
                    : " " + finalText;
            if (finishReason == FinishReason.MAX_ITERATIONS) {
                return new WorkerNoReportCause(
                        "worker_iteration_budget_exhausted",
                        "Worker reached max iterations before worker_report",
                        "Launcher stopped: worker exhausted iteration budget before reporting",
                        "Worker reached max iterations before worker_report." + suffix,
                        finishReason,
                        finalText);
            }
            if (finishReason == FinishReason.MODEL_TRUNCATED) {
                return new WorkerNoReportCause(
                        "worker_model_truncated",
                        "Worker response was truncated before worker_report",
                        "Launcher stopped: worker response was truncated before reporting",
                        "Worker response was truncated before worker_report.",
                        finishReason,
                        finalText);
            }
            if (finishReason == FinishReason.API_ERROR) {
                return new WorkerNoReportCause(
                        "worker_api_error",
                        "Worker turn ended with API error before worker_report",
                        "Launcher stopped: worker API error before reporting",
                        "Worker turn ended with API error before worker_report." + suffix,
                        finishReason,
                        finalText);
            }
            if (finishReason == FinishReason.CANCELLED
                    || finishReason == FinishReason.PERMISSION_CANCELLED) {
                return new WorkerNoReportCause(
                        "worker_cancelled",
                        "Worker turn was cancelled before worker_report",
                        "Launcher stopped: worker was cancelled before reporting",
                        "Worker turn was cancelled before worker_report." + suffix,
                        finishReason,
                        finalText);
            }
            return new WorkerNoReportCause(
                    "no_report",
                    "Worker did not produce a report",
                    "Launcher stopped: worker did not report",
                    "Worker did not produce a worker_report. The worker session may have failed.",
                    finishReason,
                    finalText);
        }

        Map<String, String> workerDetails(int cycle, String workerSessionId) {
            return details(Map.of(
                    "cycle", String.valueOf(cycle),
                    "reason", reason,
                    "workerSessionId", workerSessionId == null ? "" : workerSessionId));
        }

        Map<String, String> launcherDetails() {
            return details(Map.of("reason", reason));
        }

        private Map<String, String> details(Map<String, String> base) {
            java.util.LinkedHashMap<String, String> details = new java.util.LinkedHashMap<>(base);
            if (finishReason != null) {
                details.put("finishReason", finishReason.name());
            }
            if (finalText != null && !finalText.isBlank()) {
                details.put("finalText", finalText);
            }
            return Map.copyOf(details);
        }

        private static String sanitizeFinalText(String value) {
            if (value == null) {
                return null;
            }
            String compact = value.replaceAll("\\s+", " ").trim();
            return compact.length() <= 240 ? compact : compact.substring(0, 240);
        }
    }
}
