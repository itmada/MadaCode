package madacode.cli;

import madacode.cli.session.SessionChooser;
import madacode.cli.slash.SlashAction;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.cli.slash.SlashContext;
import madacode.core.ConversationSession;
import madacode.core.QueryEngine;
import madacode.core.SessionStorage;
import madacode.core.SessionStorageException;
import madacode.core.TurnExecutor;
import madacode.core.TurnHandle;
import madacode.services.compact.CompactPlanner;
import madacode.provider.ProviderRegistry;
import madacode.render.ExpandableHistory;
import madacode.render.HistoryPrinter;
import madacode.render.MetaEventRenderer;
import madacode.render.turn.TurnRenderer;
import madacode.tui.Screen;
import madacode.tui.theme.Tk;
import madacode.tui.widget.NotificationCenter;
import madacode.tui.widget.SessionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract sealed class Repl permits JLineRepl, BufferedRepl {

    final QueryEngine queryEngine;
    volatile ConversationSession session;
    final SessionStorage sessionStorage;
    final SlashCommandHandler slashHandler;
    final Screen screen;
    final TurnRenderer turnRenderer;
    final MetaEventRenderer metaEventRenderer;
    final HistoryPrinter historyPrinter;
    final SessionContext sessionContext;
    final NotificationCenter notifications;
    InterruptController interruptController;
    final TurnExecutor turnExecutor;
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
        this.notifications = config.notifications;
        this.shutdownTargets = config.shutdownTargets != null
                ? new ArrayList<>(config.shutdownTargets) : new ArrayList<>();
        this.metaEventRenderer = new MetaEventRenderer(screen, sessionContext);
        this.slashHandler = SlashCommandHandler.builder(sessionStorage, screen)
                .sessionChooser(config.sessionChooser)
                .registry(Objects.requireNonNull(config.slashRegistry, "slashRegistry"))
                .queryEngine(queryEngine)
                .providerRegistry(config.providerRegistry)
                .compactPlanner(config.compactPlanner)
                .sessionContext(sessionContext)
                .modelChooser(config.modelChooser)
                .themeChooser(config.themeChooser)
                .providerChooser(config.providerChooser)
                .clearScreen(config.clearScreen)
                .notifications(notifications)
                .build();
        session.addListener(turnRenderer);
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
                session.addInput(line);
                String expanded = AtFileCompleter.expandMentions(line, session);
                runManagedTurn(turnExecutor.submit(session, expanded));
                yield true;
            }
            case SlashAction.RunLocalTurn r -> {
                runManagedTurn(turnExecutor.submitLocal(session, r.label(), r.task()));
                yield true;
            }
            case SlashAction.Handled h -> true;
            case SlashAction.SwitchSession s -> {
                replaceSession(s.session());
                yield true;
            }
            case SlashAction.ReplayAll r -> {
                turnRenderer.reset();
                metaEventRenderer.reset();
                historyPrinter.printAll(session.messages());
                yield true;
            }
            case SlashAction.Cleared c -> {
                turnRenderer.reset();
                metaEventRenderer.reset();
                yield true;
            }
            case SlashAction.Exit e -> {
                persistSession();
                yield false;
            }
        };
    }

    public TurnRenderer turnRenderer() { return turnRenderer; }

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
        persistSession();
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
            session.addMessage(madacode.core.Message.system(summary));
        } catch (RuntimeException ignored) {
            // The session itself is in a state where even a SYSTEM message
            // can't be appended; we've already surfaced the error to the user.
        }
    }

    final void replaceSession(ConversationSession newSession) {
        session.removeListener(turnRenderer);
        session.removeListener(metaEventRenderer);
        turnRenderer.reset();
        metaEventRenderer.reset();
        this.session = newSession;
        newSession.addListener(turnRenderer);
        newSession.addListener(metaEventRenderer);
        onSessionReplaced(newSession);
        screen.scrollback("Switched to session: " + newSession.sessionId());
        screen.scrollback("");
        historyPrinter.printAll(newSession.messages());
    }

    protected void onSessionReplaced(ConversationSession newSession) {}

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
        SlashContext.ThemeChooser themeChooser;
        SlashContext.ProviderChooser providerChooser;
        Runnable clearScreen;
        NotificationCenter notifications;
        List<AutoCloseable> shutdownTargets;
    }
}
