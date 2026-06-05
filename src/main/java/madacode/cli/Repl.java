package madacode.cli;

import madacode.cli.mode.CommonModeHandler;
import madacode.cli.mode.LongRunningModeHandler;
import madacode.cli.mode.ModeExecution;
import madacode.cli.mode.ModeRouter;
import madacode.cli.session.SessionChooser;
import madacode.cli.slash.SlashAction;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.cli.slash.SlashContext;
import madacode.core.model.Message;
import madacode.core.model.MetaEvent;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTransitionRequest;
import madacode.core.session.SessionListener;
import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorageException;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnHandle;
import madacode.services.compact.CompactPlanner;
import madacode.provider.ActiveState;
import madacode.provider.ProviderRegistry;
import madacode.render.ExpandableHistory;
import madacode.render.BlockSpacing;
import madacode.render.HistoryPrinter;
import madacode.render.MetaEventRenderer;
import madacode.render.turn.TurnRenderer;
import madacode.tui.Screen;
import madacode.tui.WelcomeCard;
import madacode.tui.theme.Tk;
import madacode.tui.widget.NotificationCenter;
import madacode.tui.widget.SessionContext;

import madacode.longrunning.LongRunningController;
import madacode.longrunning.LongRunningLauncher;
import madacode.longrunning.LongRunningRuntime;
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.LongRunningWorkerRunner;
import madacode.permission.ApprovalResponse;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Repl {

    final QueryEngine queryEngine;
    volatile ConversationSession session;
    final SessionStorage sessionStorage;
    final SlashCommandHandler slashHandler;
    final Screen screen;
    final TurnRenderer turnRenderer;
    final MetaEventRenderer metaEventRenderer;
    final SessionModeSyncListener sessionModeSyncListener;
    final HistoryPrinter historyPrinter;
    final SessionContext sessionContext;
    final ProviderRegistry providerRegistry;
    final NotificationCenter notifications;
    InterruptController interruptController;
    final TurnExecutor turnExecutor;
    final ModeRouter modeRouter;
    final LongRunningLauncher launcher;
    final LongRunningRuntime longRunningRuntime;
    final LongRunningController longRunningController;
    final UserPromptChannel promptChannel;
    final PermissionGate permissionGate;
    final Path workerTurnLogRoot;
    private final List<AutoCloseable> shutdownTargets;
    private final ConcurrentLinkedQueue<LongRunningRuntime.Completion> longRunningCompletions =
            new ConcurrentLinkedQueue<>();

    Repl(Config config) {
        this.queryEngine = Objects.requireNonNull(config.queryEngine, "queryEngine");
        this.turnExecutor = Objects.requireNonNull(config.turnExecutor, "turnExecutor");
        this.session = Objects.requireNonNull(config.session, "session");
        this.screen = Objects.requireNonNull(config.screen, "screen");
        this.sessionStorage = Objects.requireNonNull(config.sessionStorage, "sessionStorage");
        this.turnRenderer = Objects.requireNonNull(config.turnRenderer, "turnRenderer");
        this.historyPrinter = new HistoryPrinter(screen, config.expandableHistory);
        this.sessionContext = config.sessionContext;
        this.providerRegistry = config.providerRegistry;
        this.notifications = config.notifications;
        this.permissionGate = config.permissionGate;
        this.workerTurnLogRoot = config.workerTurnLogRoot;
        this.modeRouter = config.modeRouter != null
                ? config.modeRouter
                : new ModeRouter(
                        new CommonModeHandler(turnExecutor),
                        new LongRunningModeHandler(turnExecutor));
        this.launcher = config.launcher;
        this.longRunningRuntime = config.longRunningRuntime != null
                ? config.longRunningRuntime
                : createLongRunningRuntime();
        this.longRunningController = config.longRunningController != null
                ? config.longRunningController
                : new LongRunningController();
        this.promptChannel = config.promptChannel != null
                ? config.promptChannel
                : UnavailablePromptChannel.INSTANCE;
        this.shutdownTargets = config.shutdownTargets != null
                ? new ArrayList<>(config.shutdownTargets) : new ArrayList<>();
        this.metaEventRenderer = new MetaEventRenderer(screen, sessionContext);
        this.sessionModeSyncListener = new SessionModeSyncListener(sessionContext, session);
        this.slashHandler = SlashCommandHandler.builder(sessionStorage, screen)
                .sessionChooser(config.sessionChooser)
                .registry(Objects.requireNonNull(config.slashRegistry, "slashRegistry"))
                .queryEngine(queryEngine)
                .providerRegistry(config.providerRegistry)
                .compactPlanner(config.compactPlanner)
                .sessionContext(sessionContext)
                .modelChooser(config.modelChooser)
                .modeChooser(config.modeChooser)
                .permissionChooser(config.permissionChooser)
                .themeChooser(config.themeChooser)
                .providerChooser(config.providerChooser)
                .notifications(notifications)
                .build();
        session.addListener(turnRenderer);
        session.addListener(sessionModeSyncListener);
        session.addListener(metaEventRenderer);
    }

    public abstract void run();

    public String foregroundSessionId() {
        return session.sessionId();
    }

    final boolean handleLine(String line) {
        drainLongRunningRuntimeCompletions();
        SlashAction action = slashHandler.handle(line, session);
        boolean keepRunning = switch (action) {
            case SlashAction.Continue c -> {
                ModeExecution execution = modeRouter.handle(line, session);
                runExecutionChain(execution, 1);
                yield true;
            }
            case SlashAction.RunLocalTurn r -> {
                runManagedTurn(turnExecutor.submitLocal(session, r.label(), r.task()));
                yield true;
            }
            case SlashAction.Handled h -> {
                processPendingLongRunningTransitionRequest();
                if (h.persistSession()) {
                    persistSession();
                }
                yield true;
            }
            case SlashAction.SwitchSession s -> {
                replaceSession(s.session(), s.fresh());
                persistSession();
                yield true;
            }
            case SlashAction.ReplayAll r -> {
                turnRenderer.reset();
                metaEventRenderer.reset();
                historyPrinter.printAll(session.messages());
                yield true;
            }
            case SlashAction.Exit e -> {
                persistSession();
                yield false;
            }
        };
        drainLongRunningRuntimeCompletions();
        return keepRunning;
    }

    public TurnRenderer turnRenderer() { return turnRenderer; }

    private LongRunningRuntime createLongRunningRuntime() {
        LongRunningLauncher effectiveLauncher = launcher;
        if (effectiveLauncher != null) {
            return new LongRunningRuntime(effectiveLauncher);
        }
        if (permissionGate == null) {
            return null;
        }
        Path effectiveTurnLogRoot = workerTurnLogRoot != null
                ? workerTurnLogRoot
                : sessionStorage.transcriptPath(session.sessionId()).getParent();
        LongRunningWorkerRunner.QueryEngineFactory engineFactory = (toolRegistry, promptBuilder) ->
                new madacode.core.engine.QueryEngine(
                        queryEngine.apiClient(), toolRegistry, promptBuilder,
                        longRunningWorkerPermissionGate());
        LongRunningWorkerRunner workerRunner = new LongRunningWorkerRunner(
                engineFactory, sessionStorage, queryEngine.toolRegistry(), effectiveTurnLogRoot);
        return new LongRunningRuntime(new LongRunningLauncher(workerRunner));
    }

    static PermissionGate longRunningWorkerPermissionGate() {
        return new DefaultPermissionGate((tool, input) -> ApprovalResponse.DENY);
    }

    protected boolean startLongRunningRuntime() {
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            recordLongRunningControllerEvent("worker_runtime_start_failed",
                    Map.of("reason", "no_active_task"));
            screen.scrollback("No active long-running task.");
            markLongRunningInterrupted("runtime_start_failed");
            return false;
        }
        if (longRunningRuntime == null) {
            recordLongRunningControllerEvent("worker_runtime_start_failed",
                    Map.of("reason", "runtime_unavailable"));
            screen.scrollback("Cannot launch long-running workers: permission gate is not configured.");
            markLongRunningInterrupted("runtime_start_failed");
            return false;
        }
        boolean started;
        try {
            started = longRunningRuntime.start(
                    taskId,
                    session.workingDirectory(),
                    session,
                    longRunningCompletions::add);
        } catch (RuntimeException exception) {
            recordLongRunningControllerEvent("worker_runtime_start_failed",
                    Map.of(
                            "reason", "runtime_exception",
                            "detail", exception.getMessage() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getMessage()));
            screen.scrollback("Failed to start long-running runtime: "
                    + (exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
            markLongRunningInterrupted("runtime_start_failed");
            return false;
        }
        if (!started) {
            recordLongRunningControllerEvent("worker_runtime_already_running", Map.of());
            screen.scrollback("Long-running workers are already running for this task.");
        } else {
            recordLongRunningControllerEvent("worker_runtime_started", Map.of());
        }
        return started;
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

    final void drainLongRunningRuntimeCompletions() {
        LongRunningRuntime.Completion completion;
        while ((completion = longRunningCompletions.poll()) != null) {
            applyLongRunningRuntimeCompletion(completion);
        }
    }

    final void applyLongRunningRuntimeCompletion(LongRunningRuntime.Completion completion) {
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
            markLongRunningInterrupted("runtime_failed");
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
        recordLongRunningControllerEvent("worker_runtime_finished", fields);
        session.addMessage(Message.system("[long-running] " + summary));
        persistSession();
    }

    protected final void markLongRunningInterrupted(String reason) {
        session.setLongRunningStage(LongRunningStage.INTERRUPT);
        session.setLongRunningReason(reason);
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        recordLongRunningControllerEvent("task_marked_interrupted",
                Map.of("reason", reason == null ? "" : reason));
        try {
            new LongRunningTaskStore(session.workingDirectory()).markTaskInterrupted(taskId, reason);
        } catch (RuntimeException exception) {
            screen.scrollback(Tk.errorTag("long-running") + " "
                    + "Failed to mark task INTERRUPT: " + exception.getMessage());
        }
    }

    private static String safeTaskId(String taskId) {
        return taskId == null || taskId.isBlank() ? "<none>" : taskId;
    }

    private Optional<LongRunningStage> stageFromTaskStore(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        try {
            return LongRunningStage.fromWire(
                    new LongRunningTaskStore(session.workingDirectory()).loadTask(taskId).status());
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
            LongRunningTaskStore store = new LongRunningTaskStore(session.workingDirectory());
            String currentStatus = store.loadTask(taskId).status();
            if (!"DONE".equals(currentStatus) && !"INTERRUPT".equals(currentStatus)) {
                store.markTaskInterrupted(taskId, interruptReasonFor(status));
            }
        } catch (RuntimeException exception) {
            screen.scrollback(Tk.errorTag("long-running") + " "
                    + "Failed to mark task INTERRUPT: " + exception.getMessage());
        }
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

    private void runManagedTurn(TurnHandle handle) {
        screen.setCursorVisible(false);
        if (interruptController != null) {
            interruptController.beginTurn(handle.turnId(), handle.cancel());
        }
        try {
            handle.result().join();
        } catch (java.util.concurrent.CompletionException ce) {
            renderTurnCrash(ce.getCause() != null ? ce.getCause() : ce);
        } finally {
            screen.setCursorVisible(true);
            if (interruptController != null) {
                interruptController.endTurn();
            }
        }
        session.fireTurnEnd();
        processPendingLongRunningTransitionRequest();
        persistSession();
    }

    private void processPendingLongRunningTransitionRequest() {
        session.pendingLongRunningTransitionRequest()
                .ifPresent(this::handlePendingLongRunningTransitionRequest);
    }

    private void handlePendingLongRunningTransitionRequest(LongRunningTransitionRequest request) {
        recordLongRunningTransitionPromptEvent("transition_confirmation_requested", request);
        boolean approved = promptChannel.confirm(longRunningTransitionPrompt(request));
        recordLongRunningTransitionPromptEvent(
                approved ? "transition_confirmation_approved" : "transition_confirmation_rejected",
                request);
        try {
            if (approved) {
                LongRunningController.AppliedTransition applied =
                        longRunningController.applyPendingRequest(session, "user", interruptController);
                if (applied.targetStage() == LongRunningStage.RUNNING) {
                    if (startLongRunningRuntime()) {
                        screen.scrollback("");
                        screen.scrollback("[long-running] Worker runtime started; monitor active.");
                    }
                }
            } else {
                longRunningController.rejectPendingRequest(session, "user");
            }
        } catch (RuntimeException exception) {
            screen.scrollback("Failed to apply long-running transition: " + exception.getMessage());
        }
    }

    protected static String longRunningTransitionPrompt(LongRunningTransitionRequest request) {
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

    protected final void recordLongRunningControllerEvent(String event, Map<String, String> fields) {
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

    private void recordLongRunningTransitionPromptEvent(
            String event,
            LongRunningTransitionRequest request) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("transition", request.sourceStage() + " -> " + request.targetStage());
        fields.put("reason", request.reason());
        if (request.summary() != null) {
            fields.put("summary", request.summary());
        }
        recordLongRunningControllerEvent(event, fields);
    }

    private Optional<ModeExecution> runAfterTurnHook(ModeExecution.AfterTurn afterTurn) {
        try {
            return afterTurn.run();
        } catch (RuntimeException exception) {
            renderTurnCrash(exception);
            return Optional.empty();
        }
    }

    private void runExecutionChain(ModeExecution execution, int maxChainedTurns) {
        ModeExecution current = execution;
        int chainedTurns = 0;
        while (current != null) {
            runManagedTurn(current.handle());
            Optional<ModeExecution> next = runAfterTurnHook(current.afterTurn());
            persistSession();
            if (next.isEmpty()) {
                break;
            }
            if (++chainedTurns > maxChainedTurns) {
                break;
            }
            current = next.get();
        }
    }

    /** Surface a supervised turn crash to the user. Best-effort: appending a
     *  marker to the transcript is itself guarded — if even THAT throws (e.g.
     *  the session is in a doubly-broken state), we still keep the REPL alive. */
    private void renderTurnCrash(Throwable error) {
        String message = error == null ? "unknown error" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        String summary = "[turn crashed: " + message + "]";
        if (notifications != null) {
            notifications.warn(summary);
        } else {
            screen.scrollback(Tk.warnTag("error") + " " + summary);
        }
        try {
            session.addMessage(madacode.core.model.Message.system(summary));
        } catch (RuntimeException ignored) {
            // The session itself is in a state where even a SYSTEM message
            // can't be appended; we've already surfaced the error to the user.
        }
    }

    final void replaceSession(ConversationSession newSession, boolean fresh) {
        session.removeListener(turnRenderer);
        session.removeListener(sessionModeSyncListener);
        session.removeListener(metaEventRenderer);
        turnRenderer.reset();
        metaEventRenderer.reset();
        this.session = newSession;
        sessionModeSyncListener.setSession(newSession);
        newSession.addListener(turnRenderer);
        newSession.addListener(sessionModeSyncListener);
        newSession.addListener(metaEventRenderer);
        onSessionReplaced(newSession, fresh);
        if (fresh) {
            renderFreshSessionHeader(newSession);
        } else {
            renderSwitchedSessionHeader(newSession);
        }
        historyPrinter.printAll(newSession.messages());
    }

    protected void onSessionReplaced(ConversationSession newSession, boolean fresh) {}

    protected void renderSwitchedSessionHeader(ConversationSession newSession) {
        BlockSpacing.scrollbackBlock(screen,
                Tk.dim("Switched to session: " + newSession.sessionId()));
    }

    private void renderFreshSessionHeader(ConversationSession newSession) {
        ActiveModel activeModel = currentActiveModel();
        BlockSpacing.scrollbackBlock(screen,
                WelcomeCard.render(
                        activeModel.provider(),
                        activeModel.model(),
                        newSession.workingDirectory(),
                        screen.width()));
    }

    private ActiveModel currentActiveModel() {
        if (providerRegistry != null) {
            ActiveState active = providerRegistry.active();
            return new ActiveModel(active.provider().name(), active.currentModel().name());
        }
        if (sessionContext != null && sessionContext.model() != null) {
            return new ActiveModel("unknown", sessionContext.model());
        }
        return new ActiveModel("unknown", "unknown");
    }

    private record ActiveModel(String provider, String model) {}

    private static final class SessionModeSyncListener implements SessionListener {
        private final SessionContext sessionContext;
        private volatile ConversationSession session;

        SessionModeSyncListener(SessionContext sessionContext, ConversationSession session) {
            this.sessionContext = sessionContext;
            this.session = session;
            sync();
        }

        void setSession(ConversationSession session) {
            this.session = session;
            sync();
        }

        @Override
        public void onMetaEvent(MetaEvent meta) {
            if (meta instanceof MetaEvent.PlanModeEntered
                    || meta instanceof MetaEvent.PlanModeExited
                    || meta instanceof MetaEvent.PlanRejected) {
                sync();
            }
        }

        private void sync() {
            if (sessionContext != null && session != null) {
                sessionContext.syncFrom(session);
            }
        }
    }

    final void persistSession() {
        try {
            sessionStorage.save(session);
        } catch (SessionStorageException e) {
            String message = "Failed to save transcript: " + e.getMessage();
            if (notifications != null) {
                notifications.warn(message);
            } else {
                screen.scrollback(Tk.warnTag("warn") + " " + message);
            }
        }
    }

    static boolean isExitCommand(String line) {
        String t = line.trim();
        return "exit".equalsIgnoreCase(t) || "quit".equalsIgnoreCase(t);
    }

    protected void closeResources() {
        if (longRunningRuntime != null) {
            longRunningRuntime.close();
        }
        for (AutoCloseable target : shutdownTargets) {
            try {
                target.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void addShutdownTarget(AutoCloseable target) {
        shutdownTargets.add(target);
    }

    static final class Config {
        QueryEngine queryEngine;
        TurnExecutor turnExecutor;
        ConversationSession session;
        Screen screen;
        SessionStorage sessionStorage;
        SlashCommandRegistry slashRegistry;
        TurnRenderer turnRenderer;
        ExpandableHistory expandableHistory;
        SessionChooser sessionChooser;
        ProviderRegistry providerRegistry;
        CompactPlanner compactPlanner;
        SessionContext sessionContext;
        SlashContext.ModelChooser modelChooser;
        SlashContext.ModeChooser modeChooser;
        SlashContext.PermissionChooser permissionChooser;
        SlashContext.ThemeChooser themeChooser;
        SlashContext.ProviderChooser providerChooser;
        NotificationCenter notifications;
        List<AutoCloseable> shutdownTargets;
        ModeRouter modeRouter;
        LongRunningLauncher launcher;
        LongRunningRuntime longRunningRuntime;
        LongRunningController longRunningController;
        UserPromptChannel promptChannel;
        PermissionGate permissionGate;
        Path workerTurnLogRoot;
    }
}
