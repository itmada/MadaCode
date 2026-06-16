package madacode.longrunning;

import madacode.cli.InterruptController;
import madacode.cli.UserPromptChannel;
import madacode.core.engine.QueryEngine;
import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTransitionRequest;
import madacode.core.session.SessionStorage;
import madacode.permission.ApprovalResponse;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;
import madacode.tui.Screen;
import madacode.tui.theme.Tk;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Coordinates REPL-side long-running task lifecycle and launcher completions.
 */
public final class LongRunningReplCoordinator implements AutoCloseable {

    private final Supplier<ConversationSession> sessionSupplier;
    private final Screen screen;
    private final LongRunningRuntime runtime;
    private final LongRunningController controller;
    private final UserPromptChannel promptChannel;
    private final Supplier<InterruptController> interruptControllerSupplier;
    private final ControllerTurnRunner controllerTurnRunner;
    private final Runnable persistSession;
    private final LongRunningController.TaskStoreFactory taskStoreFactory;
    private final ConcurrentLinkedQueue<String> pendingControllerTurns =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LongRunningRuntime.Completion> completions =
            new ConcurrentLinkedQueue<>();
    private volatile Path cachedStoreDirectory;
    private volatile LongRunningTaskStore cachedStore;

    public LongRunningReplCoordinator(
            Supplier<ConversationSession> sessionSupplier,
            Screen screen,
            LongRunningRuntime runtime,
            LongRunningController controller,
            UserPromptChannel promptChannel,
            Supplier<InterruptController> interruptControllerSupplier,
            ControllerTurnRunner controllerTurnRunner,
            Runnable persistSession,
            LongRunningController.TaskStoreFactory taskStoreFactory) {
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.runtime = runtime;
        this.controller = Objects.requireNonNull(controller, "controller");
        this.promptChannel = Objects.requireNonNull(promptChannel, "promptChannel");
        this.interruptControllerSupplier =
                Objects.requireNonNull(interruptControllerSupplier, "interruptControllerSupplier");
        this.controllerTurnRunner = Objects.requireNonNull(controllerTurnRunner, "controllerTurnRunner");
        this.persistSession = Objects.requireNonNull(persistSession, "persistSession");
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
    }

    public static LongRunningRuntime createRuntime(
            LongRunningLauncher launcher,
            PermissionGate permissionGate,
            QueryEngine queryEngine,
            SessionStorage sessionStorage,
            Supplier<ConversationSession> sessionSupplier,
            Path workerTurnLogRoot,
            LongRunningController.TaskStoreFactory taskStoreFactory) {
        if (launcher != null) {
            return new LongRunningRuntime(launcher);
        }
        if (permissionGate == null) {
            return null;
        }
        ConversationSession session = sessionSupplier.get();
        Path effectiveTurnLogRoot = workerTurnLogRoot != null
                ? workerTurnLogRoot
                : sessionStorage.transcriptPath(session.sessionId()).getParent();
        LongRunningWorkerRunner.QueryEngineFactory engineFactory = (toolRegistry, promptBuilder) ->
                new QueryEngine(
                        queryEngine.apiClient(), toolRegistry, promptBuilder,
                        longRunningWorkerPermissionGate());
        LongRunningWorkerRunner workerRunner = new LongRunningWorkerRunner(
                engineFactory, sessionStorage, queryEngine.toolRegistry(), effectiveTurnLogRoot);
        return new LongRunningRuntime(new LongRunningLauncher(workerRunner, taskStoreFactory));
    }

    public boolean startRuntime() {
        ConversationSession session = session();
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            recordControllerEvent("worker_runtime_start_failed",
                    Map.of("reason", "no_active_task"));
            screen.scrollback("No active long-running task.");
            markInterrupted("runtime_start_failed");
            return false;
        }
        if (runtime == null) {
            recordControllerEvent("worker_runtime_start_failed",
                    Map.of("reason", "runtime_unavailable"));
            screen.scrollback("Cannot launch long-running workers: permission gate is not configured.");
            markInterrupted("runtime_start_failed");
            return false;
        }
        boolean started;
        try {
            started = runtime.start(
                    taskId,
                    session.workingDirectory(),
                    session,
                    completion -> {
                        completions.add(completion);
                        screen.notifyAsync(Tk.dim(asyncCompletionNotification(completion)));
                    });
        } catch (RuntimeException exception) {
            recordControllerEvent("worker_runtime_start_failed",
                    Map.of(
                            "reason", "runtime_exception",
                            "detail", exception.getMessage() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getMessage()));
            screen.scrollback("Failed to start long-running runtime: "
                    + (exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
            markInterrupted("runtime_start_failed");
            return false;
        }
        if (!started) {
            recordControllerEvent("worker_runtime_already_running", Map.of());
            screen.scrollback("Long-running workers are already running for this task.");
        } else {
            recordControllerEvent("worker_runtime_started", Map.of());
        }
        return started;
    }

    public void drainCompletions() {
        LongRunningRuntime.Completion completion;
        while ((completion = completions.poll()) != null) {
            applyCompletion(completion);
        }
    }

    public void drainPendingControllerTurns() {
        String prompt;
        while ((prompt = pendingControllerTurns.poll()) != null) {
            controllerTurnRunner.run(prompt);
        }
    }

    public void processPendingTransitionRequest() {
        session().pendingLongRunningTransitionRequest()
                .ifPresent(this::handlePendingTransitionRequest);
    }

    public void markInterrupted(String reason) {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.INTERRUPT);
        session.setLongRunningReason(reason);
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        recordControllerEvent("task_marked_interrupted",
                Map.of("reason", reason == null ? "" : reason));
        try {
            taskStore(session).markTaskInterrupted(taskId, reason);
        } catch (RuntimeException exception) {
            screen.scrollback(Tk.errorTag("long-running") + " "
                    + "Failed to mark task INTERRUPT: " + exception.getMessage());
        }
    }

    public void recordControllerEvent(String event, Map<String, String> fields) {
        ConversationSession session = session();
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        ordered.put("event", event);
        ordered.put("task", safeTaskId(session.longRunningTaskId()));
        LongRunningStage stage = session.longRunningStage();
        if (stage != null) {
            ordered.put("stage", stage.name());
        }
        if (fields != null) {
            ordered.putAll(fields);
        }
        session.addControllerEvent("long-running", ordered);
    }

    public boolean isRuntimeRunning() {
        return runtime != null && runtime.isRunning();
    }

    public boolean interruptRuntime(String reason) {
        return runtime != null && runtime.interrupt(reason);
    }

    @Override
    public void close() {
        if (runtime != null) {
            runtime.close();
            drainCompletions();
        }
    }

    private void applyCompletion(LongRunningRuntime.Completion completion) {
        ConversationSession session = session();
        if (!Objects.equals(session.longRunningTaskId(), completion.taskId())) {
            screen.scrollback("[stale] Ignored long-running launcher completion for task "
                    + safeTaskId(completion.taskId()) + "; current task is "
                    + safeTaskId(session.longRunningTaskId()) + ".");
            return;
        }
        String summary;
        LongRunningStage targetStage;
        if (completion.error() != null) {
            Throwable error = completion.error();
            summary = "[failed] Long-running launcher failed: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            markInterrupted("runtime_failed");
            targetStage = LongRunningStage.INTERRUPT;
        } else {
            LongRunningLauncher.LaunchResult result = completion.result();
            summary = longRunningResultSummary(result);
            LongRunningStage fallbackStage = switch (result.status()) {
                case COMPLETED -> LongRunningStage.DONE;
                case ALREADY_RUNNING, BLOCKED, FAILED, NEEDS_USER, INTERRUPTED, MAX_WORKERS_EXHAUSTED ->
                        LongRunningStage.INTERRUPT;
            };
            if (fallbackStage == LongRunningStage.INTERRUPT) {
                markTaskInterruptedIfNeeded(completion.taskId(), result.status());
            }
            targetStage = stageFromTaskStore(completion.taskId())
                    .filter(stage -> stage == LongRunningStage.DONE || stage == LongRunningStage.INTERRUPT)
                    .orElse(fallbackStage);
        }
        screen.scrollback("");
        screen.scrollback(Tk.dim(summary));
        session.setLongRunningStage(targetStage);
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("summary", summary);
        fields.put("result_stage", targetStage.name());
        if (completion.result() != null) {
            fields.put("launcher_status", completion.result().status().name());
            fields.put("workers_launched", String.valueOf(completion.result().workersLaunched()));
        }
        if (completion.error() != null) {
            fields.put("error", completion.error().getMessage() == null
                    ? completion.error().getClass().getSimpleName()
                    : completion.error().getMessage());
        }
        recordControllerEvent("worker_runtime_finished", fields);
        session.addMessage(Message.system("[long-running] " + summary));
        persistSession.run();
    }

    private void handlePendingTransitionRequest(LongRunningTransitionRequest request) {
        recordTransitionPromptEvent("transition_confirmation_requested", request);
        boolean approved = promptChannel.confirm(longRunningTransitionPrompt(request));
        recordTransitionPromptEvent(
                approved ? "transition_confirmation_approved" : "transition_confirmation_rejected",
                request);
        try {
            if (approved) {
                LongRunningController.AppliedTransition applied =
                        controller.applyPendingRequest(session(), "user", interruptControllerSupplier.get());
                if (applied.targetStage() == LongRunningStage.RUNNING) {
                    if (startRuntime()) {
                        screen.scrollback("");
                        screen.scrollback("[long-running] Worker runtime started; monitor active.");
                    }
                }
            } else {
                controller.rejectPendingRequest(session(), "user");
            }
        } catch (RuntimeException exception) {
            screen.scrollback("Failed to apply long-running transition: " + exception.getMessage());
        }
    }

    private Optional<LongRunningStage> stageFromTaskStore(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        try {
            return LongRunningStage.fromWire(taskStore(session()).loadTask(taskId).status());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void markTaskInterruptedIfNeeded(String taskId, LongRunningLauncher.LaunchStatus status) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        if (status == LongRunningLauncher.LaunchStatus.ALREADY_RUNNING) {
            return;
        }
        try {
            LongRunningTaskStore store = taskStore(session());
            String currentStatus = store.loadTask(taskId).status();
            if (!"DONE".equals(currentStatus) && !"INTERRUPT".equals(currentStatus)) {
                store.markTaskInterrupted(taskId, interruptReasonFor(status));
            }
        } catch (RuntimeException exception) {
            screen.scrollback(Tk.errorTag("long-running") + " "
                    + "Failed to mark task INTERRUPT: " + exception.getMessage());
        }
    }

    private void recordTransitionPromptEvent(
            String event,
            LongRunningTransitionRequest request) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("transition", request.sourceStage() + " -> " + request.targetStage());
        fields.put("reason", request.reason());
        if (request.summary() != null) {
            fields.put("summary", request.summary());
        }
        recordControllerEvent(event, fields);
    }

    private LongRunningTaskStore taskStore(ConversationSession session) {
        Path workingDirectory = session.workingDirectory();
        LongRunningTaskStore store = cachedStore;
        if (store == null || !Objects.equals(cachedStoreDirectory, workingDirectory)) {
            store = taskStoreFactory.create(workingDirectory);
            cachedStore = store;
            cachedStoreDirectory = workingDirectory;
        }
        return store;
    }

    private ConversationSession session() {
        return sessionSupplier.get();
    }

    private static String longRunningResultSummary(LongRunningLauncher.LaunchResult result) {
        String statusTag = switch (result.status()) {
            case COMPLETED -> "[completed]";
            case ALREADY_RUNNING -> "[already-running]";
            case BLOCKED -> "[blocked]";
            case FAILED -> "[failed]";
            case NEEDS_USER -> "[needs-user]";
            case INTERRUPTED -> "[interrupted]";
            case MAX_WORKERS_EXHAUSTED -> "[exhausted]";
        };
        return statusTag + " " + result.message()
                + " (" + result.workersLaunched() + " worker cycle(s) launched)";
    }

    private static String asyncCompletionNotification(LongRunningRuntime.Completion completion) {
        String summary = completionSummary(completion);
        return "[long-running] Worker runtime completed: " + summary;
    }

    private static String completionSummary(LongRunningRuntime.Completion completion) {
        if (completion.error() != null) {
            Throwable error = completion.error();
            return "[failed] Long-running launcher failed: "
                    + (error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage());
        }
        return longRunningResultSummary(completion.result());
    }

    private static String longRunningTransitionPrompt(LongRunningTransitionRequest request) {
        LongRunningStage source = request.sourceStage().normalized();
        LongRunningStage target = request.targetStage().normalized();
        String suffix = request.summary() == null ? "" : "\n\n" + request.summary();
        if (source == LongRunningStage.DRAFT && target == LongRunningStage.RUNNING) {
            return "Start this long-running task now?" + suffix;
        }
        if (source == LongRunningStage.INTERRUPT && target == LongRunningStage.RUNNING) {
            return "Resume this long-running task now?" + suffix;
        }
        if (target == LongRunningStage.DONE) {
            return "Mark this long-running task DONE?" + suffix;
        }
        return "Apply long-running transition " + source + " -> " + target + "?" + suffix;
    }

    private static String interruptReasonFor(LongRunningLauncher.LaunchStatus status) {
        return switch (status) {
            case ALREADY_RUNNING -> "already_running";
            case BLOCKED -> "worker_blocked";
            case FAILED -> "worker_failed";
            case NEEDS_USER -> "needs_user";
            case INTERRUPTED -> "user_interrupted";
            case MAX_WORKERS_EXHAUSTED -> "worker_cycle_budget_exhausted";
            case COMPLETED -> "task_completed";
        };
    }

    private static String safeTaskId(String taskId) {
        return taskId == null || taskId.isBlank() ? "<none>" : taskId;
    }

    private static PermissionGate longRunningWorkerPermissionGate() {
        return new DefaultPermissionGate((tool, input) -> ApprovalResponse.DENY);
    }

    @FunctionalInterface
    public interface ControllerTurnRunner {
        void run(String prompt);
    }
}
