package madacode.cli;

import madacode.cli.mode.CommonModeHandler;
import madacode.cli.mode.LongRunningModeHandler;
import madacode.cli.mode.ModeExecution;
import madacode.cli.mode.ModeRouter;
import madacode.cli.session.SessionChooser;
import madacode.cli.session.SessionPointer;
import madacode.cli.slash.SlashAction;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.cli.slash.SlashContext;
import madacode.core.model.Message;
import madacode.core.model.MetaEvent;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
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
import madacode.longrunning.LongRunningReplCoordinator;
import madacode.longrunning.LongRunningRuntime;
import madacode.longrunning.LongRunningTaskStore;
import madacode.permission.PermissionGate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    final LongRunningReplCoordinator longRunningCoordinator;
    final UserPromptChannel promptChannel;
    private final List<AutoCloseable> shutdownTargets;
    private volatile Path longRunningTaskStoreDirectory;
    private volatile LongRunningTaskStore longRunningTaskStore;

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
                        new LongRunningModeHandler(turnExecutor, this::longRunningTaskStore));
        LongRunningRuntime longRunningRuntime = config.longRunningRuntime != null
                ? config.longRunningRuntime
                : LongRunningReplCoordinator.createRuntime(
                        config.launcher,
                        config.permissionGate,
                        queryEngine,
                        sessionStorage,
                        () -> session,
                        config.workerTurnLogRoot,
                        this::longRunningTaskStore);
        LongRunningController longRunningController = config.longRunningController != null
                ? config.longRunningController
                : new LongRunningController(this::longRunningTaskStore);
        this.promptChannel = config.promptChannel != null
                ? config.promptChannel
                : UnavailablePromptChannel.INSTANCE;
        this.longRunningCoordinator = new LongRunningReplCoordinator(
                () -> session,
                screen,
                longRunningRuntime,
                longRunningController,
                promptChannel,
                () -> interruptController,
                prompt -> runManagedTurn(turnExecutor.submit(session, prompt)),
                this::persistSession,
                this::longRunningTaskStore);
        this.shutdownTargets = config.shutdownTargets != null
                ? new ArrayList<>(config.shutdownTargets) : new ArrayList<>();
        this.metaEventRenderer = new MetaEventRenderer(screen, sessionContext);
        this.sessionModeSyncListener = new SessionModeSyncListener(sessionContext, session);
        this.slashHandler = SlashCommandHandler.builder(sessionStorage, screen)
                .sessionPointer(config.sessionPointer)
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

    private LongRunningTaskStore longRunningTaskStore(Path projectDirectory) {
        Path normalized = Objects.requireNonNull(projectDirectory, "projectDirectory")
                .toAbsolutePath()
                .normalize();
        LongRunningTaskStore store = longRunningTaskStore;
        if (store == null || !Objects.equals(longRunningTaskStoreDirectory, normalized)) {
            store = new LongRunningTaskStore(normalized);
            longRunningTaskStore = store;
            longRunningTaskStoreDirectory = normalized;
        }
        return store;
    }

    protected boolean startLongRunningRuntime() {
        return longRunningCoordinator.startRuntime();
    }

    final void drainLongRunningRuntimeCompletions() {
        longRunningCoordinator.drainCompletions();
    }

    protected final void drainPendingLongRunningControllerTurns() {
        longRunningCoordinator.drainPendingControllerTurns();
    }

    protected final void markLongRunningInterrupted(String reason) {
        longRunningCoordinator.markInterrupted(reason);
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
        longRunningCoordinator.processPendingTransitionRequest();
    }

    protected final void recordLongRunningControllerEvent(String event, Map<String, String> fields) {
        longRunningCoordinator.recordControllerEvent(event, fields);
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
        longRunningCoordinator.close();
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
        SessionPointer sessionPointer;
        Path inlineMemoryFile;
    }
}
