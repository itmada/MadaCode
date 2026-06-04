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
import madacode.longrunning.LongRunningWorkerRunner;
import madacode.permission.PermissionGate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        this.permissionGate = config.permissionGate;
        this.workerTurnLogRoot = config.workerTurnLogRoot;
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
        SlashAction action = slashHandler.handle(line, session);
        return switch (action) {
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
                        permissionGate);
        LongRunningWorkerRunner workerRunner = new LongRunningWorkerRunner(
                engineFactory, sessionStorage, queryEngine.toolRegistry(), effectiveTurnLogRoot);
        return new LongRunningRuntime(new LongRunningLauncher(workerRunner));
    }

    private void startLongRunningRuntime() {
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            screen.scrollback("No active long-running task.");
            return;
        }
        if (longRunningRuntime == null) {
            screen.scrollback("Cannot launch long-running workers: permission gate is not configured.");
            return;
        }
        boolean started = longRunningRuntime.start(
                taskId,
                session.workingDirectory(),
                session,
                result -> {
                    String summary = longRunningResultSummary(result);
                    screen.scrollback(summary);
                    session.addMessage(Message.system("[long-running] " + summary));
                    persistSession();
                },
                error -> {
                    String summary = "[failed] Long-running launcher failed: "
                            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    screen.scrollback(summary);
                    session.addMessage(Message.system("[long-running] " + summary));
                    persistSession();
                });
        if (started) {
            screen.scrollback("Long-running workers started in the background for task: " + taskId + ".");
        } else {
            screen.scrollback("Long-running workers are already running for this task.");
        }
    }

    private static String longRunningResultSummary(LongRunningLauncher.LaunchResult result) {
        String statusTag = switch (result.status()) {
            case COMPLETED -> "[completed]";
            case BLOCKED -> "[blocked]";
            case FAILED -> "[failed]";
            case NEEDS_USER -> "[needs-user]";
            case INTERRUPTED -> "[interrupted]";
            case MAX_WORKERS_EXHAUSTED -> "[exhausted]";
        };
        return statusTag + " " + result.message()
                + " (" + result.workersLaunched() + " worker cycle(s) launched)";
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
        boolean approved = promptChannel.confirm(longRunningTransitionPrompt(request));
        try {
            if (approved) {
                LongRunningController.AppliedTransition applied =
                        longRunningController.applyPendingRequest(session, "user", interruptController);
                if (applied.targetStage() == LongRunningStage.RUNNING) {
                    startLongRunningRuntime();
                } else if (applied.targetStage() == LongRunningStage.DRAFT
                        || applied.targetStage() == LongRunningStage.DONE) {
                    if (longRunningRuntime != null) {
                        longRunningRuntime.interrupt("longrun-transition-" + applied.targetStage().name().toLowerCase());
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
        if (source == LongRunningStage.RUNNING && target == LongRunningStage.DRAFT) {
            return "Pause worker execution and return to DRAFT?" + suffix;
        }
        if (target == LongRunningStage.DONE) {
            return "Mark this long-running task DONE?" + suffix;
        }
        return "Apply long-running transition " + source + " -> " + target + "?" + suffix;
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
