package madacode.longrunning;

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

        // Append launcher started event
        appendLauncherEvent(store, taskId, controlSession, "launcher_started",
                true, "Launcher started for task " + taskId,
                Map.of("maxWorkers", String.valueOf(maxWorkers)));

        LongRunningTaskMetadata initialMeta = store.loadTask(taskId);
        if ("DONE".equals(initialMeta.status())) {
            appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                    true, "Task is already " + initialMeta.status(),
                    Map.of("reason", "terminal_status"));
            controlSession.setLongRunningStage(madacode.core.session.LongRunningStage.DONE);
            return new LaunchResult(LaunchStatus.COMPLETED, 0, "Task already " + initialMeta.status());
        }

        try {
            LongRunningTaskMetadata executing = store.markTaskExecuting(taskId);
            if (controlSession.longRunningStage() != madacode.core.session.LongRunningStage.RUNNING) {
                controlSession.setLongRunningStage(madacode.core.session.LongRunningStage.RUNNING);
            }
            appendLauncherEvent(store, taskId, controlSession, "task_execution_started",
                    true, "Task entered execution state.",
                    Map.of("status", executing.status(), "stage", executing.stage()));
        } catch (RuntimeException e) {
            appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                    false, "Launcher could not start execution: " + e.getMessage(),
                    Map.of("reason", "execution_start_failed"));
            return new LaunchResult(LaunchStatus.FAILED, 0,
                    "Could not start long-running execution: " + e.getMessage());
        }

        for (int i = 0; i < maxWorkers; i++) {
            if (Thread.currentThread().isInterrupted()) {
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        true, "Launcher interrupted before starting next worker.",
                        Map.of("reason", "interrupted"));
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
                    return new LaunchResult(LaunchStatus.INTERRUPTED, i + 1,
                            "Launcher interrupted during worker cycle.");
                }
                appendLauncherEvent(store, taskId, controlSession, "worker_finished",
                        false, "Worker crashed: " + e.getMessage(),
                        Map.of("cycle", String.valueOf(i + 1), "error", e.getMessage()));
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        false, "Launcher stopped due to worker crash",
                        Map.of("reason", "worker_crash"));
                return new LaunchResult(LaunchStatus.FAILED, i + 1,
                        "Worker crashed: " + e.getMessage());
            }

            // Check if worker produced a report
            if (result.report().isEmpty()) {
                appendLauncherEvent(store, taskId, controlSession, "worker_finished",
                        false, "Worker did not produce a report",
                        Map.of("cycle", String.valueOf(i + 1)));
                appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                        false, "Launcher stopped: worker did not report",
                        Map.of("reason", "no_report"));
                return new LaunchResult(LaunchStatus.FAILED, i + 1,
                        "Worker did not produce a worker_report. The worker session may have failed.");
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
                        controlSession.setLongRunningStage(madacode.core.session.LongRunningStage.DONE);
                        appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                                true, "Task completed successfully",
                                Map.of("reason", "task_completed"));
                        return new LaunchResult(LaunchStatus.COMPLETED, i + 1,
                                "Task completed: " + report.summary());
                    } catch (RuntimeException e) {
                        appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                                false, "Task completion preconditions not met: " + e.getMessage(),
                                Map.of("reason", "completion_failed"));
                        return new LaunchResult(LaunchStatus.NEEDS_USER, i + 1,
                                "Worker reported task_completed but preconditions not met: " + e.getMessage());
                    }
                }
                case BLOCKED -> {
                    controlSession.setLongRunningStage(madacode.core.session.LongRunningStage.DRAFT);
                    appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                            false, "Worker blocked: " + report.summary(),
                            Map.of("reason", "blocked"));
                    return new LaunchResult(LaunchStatus.BLOCKED, i + 1,
                            "Worker blocked: " + report.summary());
                }
                case FAILED -> {
                    controlSession.setLongRunningStage(madacode.core.session.LongRunningStage.DRAFT);
                    appendLauncherEvent(store, taskId, controlSession, "launcher_stopped",
                            false, "Worker failed: " + report.summary(),
                            Map.of("reason", "worker_failed"));
                    return new LaunchResult(LaunchStatus.FAILED, i + 1,
                            "Worker failed: " + report.summary());
                }
                case NEEDS_USER -> {
                    controlSession.setLongRunningStage(madacode.core.session.LongRunningStage.DRAFT);
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
                Map.of("reason", "max_workers_exhausted"));
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
}
