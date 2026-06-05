package madacode.cli;

import madacode.cli.editor.SessionHistory;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.engine.QueryEngine;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionStorage;
import madacode.core.turn.TurnExecutor;
import madacode.services.compact.CompactPlanner;
import madacode.permission.PermissionGate;
import madacode.provider.ProviderRegistry;
import madacode.render.ExpandableHistory;
import madacode.render.BlockSpacing;
import madacode.render.Spinner;
import madacode.render.UserInputRenderer;
import madacode.render.turn.TurnRenderer;
import madacode.render.turn.TurnView;
import madacode.cli.session.SessionChooser;
import madacode.cli.session.SessionSelectModels;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.cli.slash.SlashComposer;
import madacode.cli.slash.SlashContext;
import madacode.longrunning.LongRunningMonitorReader;
import madacode.longrunning.LongRunningMonitorRenderer;
import madacode.tui.JLineScreen;
import madacode.tui.Screen;
import madacode.tui.TerminalKeys;
import madacode.tui.inline.InlineChoicePrompt;
import madacode.tui.theme.Tk;
import madacode.tui.widget.ChoicePrompt;
import madacode.tui.widget.NotificationCenter;
import madacode.tui.widget.SessionContext;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JLineRepl extends Repl {

    private final LongRunningMonitorReader longRunningMonitorReader = new LongRunningMonitorReader();
    private final LongRunningMonitorRenderer longRunningMonitorRenderer = new LongRunningMonitorRenderer();
    private final Spinner longRunningStatusSpinner = Spinner.thinking();
    private final AtomicBoolean longRunningMonitorInterruptRequested = new AtomicBoolean();

    private final Terminal terminal;
    private final JLineScreen jlineScreen;
    private final LineReader lineReader;
    private final SessionHistory sessionHistory;
    private final SlashComposer slashComposer;
    private AtomicReference<ConversationSession> currentSessionRef;

    private JLineRepl(Config config,
                      Terminal terminal,
                      JLineScreen jlineScreen,
                      LineReader lineReader,
                      SessionHistory sessionHistory,
                      SlashComposer slashComposer) {
        super(config);
        this.terminal = terminal;
        this.jlineScreen = jlineScreen;
        this.lineReader = lineReader;
        this.sessionHistory = sessionHistory;
        this.slashComposer = slashComposer;
    }

    public static Terminal createTerminal() {
        try {
            return TerminalBuilder.builder().system(true).jansi(true).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise terminal", e);
        }
    }

    public static JLineRepl create(QueryEngine queryEngine,
                                   TurnExecutor turnExecutor,
                                   ConversationSession session,
                                   SessionStorage sessionStorage,
                                   Terminal terminal,
                                   JLineScreen screen,
                                   LineReader lineReader,
                                   SessionHistory sessionHistory,
                                   SlashCommandRegistry slashRegistry,
                                   ProviderRegistry providerRegistry,
                                   CompactPlanner compactPlanner,
                                   PermissionGate permissionGate,
                                   Path workerTurnLogRoot,
                                   UserPromptChannel promptChannel,
                                   InterruptController interruptController) {
        NotificationCenter notifications = new NotificationCenter(screen);
        SessionContext ctx = new SessionContext();
        ctx.batch(() -> {
            ctx.setCwd(session.workingDirectory());
            ctx.setSessionId(session.sessionId());
            if (providerRegistry != null) {
                var active = providerRegistry.active();
                ctx.setModel(active.currentModel().name());
                ctx.setTokenLimit(active.currentModel().contextWindow());
            }
            ctx.syncFrom(session);
        });

        ExpandableHistory expandableHistory = new ExpandableHistory();

        SlashContext.ModelChooser modelChooser = inlineModelChooser(screen, terminal);
        SlashContext.ModeChooser modeChooser = inlineModeChooser(screen, terminal);
        SlashContext.PermissionChooser permissionChooser = inlinePermissionChooser(screen, terminal);
        SlashContext.ThemeChooser themeChooser = inlineThemeChooser(screen, terminal);
        SlashContext.ProviderChooser providerChooser = inlineProviderChooser(screen, terminal);
        SessionChooser sessionChooser = inlineSessionChooser(sessionStorage, screen, terminal);

        AtomicReference<ConversationSession> currentSessionRef = new AtomicReference<>(session);
        SlashComposer slashComposer = new SlashComposer(
                slashRegistry,
                () -> new SlashContext(
                        currentSessionRef.get(), screen, sessionStorage, slashRegistry, queryEngine, providerRegistry,
                        compactPlanner, ctx, Optional.ofNullable(sessionChooser),
                        Optional.of(modelChooser), Optional.of(modeChooser), Optional.of(permissionChooser),
                        Optional.of(themeChooser), Optional.of(providerChooser)),
                screen, screen, terminal);

        // Widget: at empty buffer, insert '/' and accept the line so the REPL
        // loop routes to SlashComposer.compose("/"). Intentional UX: a leading
        // '/' is always a command-palette trigger, never a filesystem path —
        // path mentions go through @file (see AtFileCompleter).
        KeyMap<Binding> main = lineReader.getKeyMaps().get(LineReader.MAIN);
        lineReader.getWidgets().put("trigger-slash-compose", () -> {
            var buf = lineReader.getBuffer();
            if (buf.length() == 0) {
                buf.write('/');
                lineReader.callWidget(LineReader.ACCEPT_LINE);
                return true;
            }
            // Not at buffer start — do a normal self-insert of '/'
            buf.write('/');
            return true;
        });
        main.bind(new Reference("trigger-slash-compose"), "/");

        TurnView turnView = new TurnView(screen);
        screen.setResizeListener(turnView::markDirty);
        TurnRenderer turnRenderer = new TurnRenderer(turnView, screen);

        Config config = new Config();
        config.queryEngine = queryEngine;
        config.turnExecutor = turnExecutor;
        config.session = session;
        config.screen = screen;
        config.sessionStorage = sessionStorage;
        config.slashRegistry = slashRegistry;
        config.turnRenderer = turnRenderer;
        config.sessionChooser = sessionChooser;
        config.providerRegistry = providerRegistry;
        config.compactPlanner = compactPlanner;
        config.sessionContext = ctx;
        config.modelChooser = modelChooser;
        config.modeChooser = modeChooser;
        config.permissionChooser = permissionChooser;
        config.themeChooser = themeChooser;
        config.providerChooser = providerChooser;
        config.notifications = notifications;
        config.expandableHistory = expandableHistory;
        config.permissionGate = permissionGate;
        config.workerTurnLogRoot = workerTurnLogRoot;
        config.promptChannel = promptChannel;

        JLineRepl repl = new JLineRepl(config, terminal, screen, lineReader, sessionHistory, slashComposer);
        repl.currentSessionRef = currentSessionRef;
        repl.interruptController = interruptController;
        return repl;
    }

    @Override
    public void run() {
        try {
            loadHistory();
            persistSession();
            replayRecentSession();

            while (true) {
                drainLongRunningRuntimeCompletions();
                drainPendingLongRunningControllerTurns();
                if (isLongRunningMonitorActive()) {
                    runLongRunningMonitorLoop();
                    drainPendingLongRunningControllerTurns();
                    loadHistory();
                    continue;
                }
                jlineScreen.enterIdlePhase();
                screen.scrollback("");
                String line;
                jlineScreen.setActiveLineReader(lineReader);
                try {
                    line = lineReader.readLine(buildPrompt());
                } catch (UserInterruptException e) {
                    screen.scrollback("");
                    screen.scrollback(Tk.dim("(type 'exit' to quit)"));
                    continue;
                } catch (EndOfFileException e) {
                    screen.scrollback("");
                    break;
                } finally {
                    jlineScreen.clearActiveLineReader();
                }

                if (line == null || isExitCommand(line)) break;
                if (line.isBlank()) continue;

                // Slash compose: when buffer was just "/", open the command palette
                if (line.equals("/")) {
                    try {
                        Optional<String> composed = slashComposer.compose(line);
                        if (composed.isEmpty()) continue; // user cancelled
                        line = composed.get();
                    } catch (IOException e) {
                        screen.scrollback(Tk.errorTag("compose") + " " + e.getMessage());
                        continue;
                    }
                }

                jlineScreen.enterTurnPhase();

                // Echo user input into scrollback (matches HistoryPrinter USER format).
                // JLine erases its own input line via ERASE_LINE_ON_FINISH; we own the scrollback record.
                screen.scrollback(UserInputRenderer.lines(line));

                String stripped = line.stripLeading();
                if (stripped.startsWith("!") && stripped.length() > 1) {
                    try {
                        runInlineBash(stripped.substring(1).stripLeading(), screen, session.workingDirectory());
                    } catch (IOException e) {
                        screen.scrollback(Tk.errorTag("bash") + " " + e.getMessage());
                    }
                    loadHistory();
                    continue;
                }
                if (stripped.startsWith("#") && stripped.length() > 1) {
                    try {
                        appendInlineMemory(stripped.substring(1).stripLeading(), screen, session.workingDirectory());
                    } catch (IOException e) {
                        screen.scrollback(Tk.errorTag("memory") + " " + e.getMessage());
                    }
                    loadHistory();
                    continue;
                }

                if (!handleLine(line)) return;
                drainLongRunningRuntimeCompletions();
                drainPendingLongRunningControllerTurns();
                loadHistory();
            }
            drainLongRunningRuntimeCompletions();
            drainPendingLongRunningControllerTurns();
            persistSession();
        } finally {
            session.removeListener(turnRenderer);
            session.removeListener(metaEventRenderer);
            turnRenderer.shutdown();
            closeResources();
            screen.shutdown();
            try {
                terminal.close();
            } catch (IOException e) {
                // Best-effort — already shutting down.
            }
        }
    }

    private boolean isLongRunningMonitorActive() {
        return session.workflowMode() == madacode.core.session.SessionMode.LONG_RUNNING
                && session.longRunningStage() == LongRunningStage.RUNNING;
    }

    private void runLongRunningMonitorLoop() {
        jlineScreen.enterTurnPhase();
        screen.setCursorVisible(false);
        longRunningMonitorInterruptRequested.set(false);
        Attributes previousAttributes = null;
        SignalCancellationBridge.Registration sigintRegistration = null;
        if (interruptController != null) {
            interruptController.pause();
        }
        try {
            previousAttributes = terminal.enterRawMode();
            sigintRegistration = new SignalCancellationBridge()
                    .activate(this::requestLongRunningMonitorInterrupt);
            if (longRunningRuntime == null || !longRunningRuntime.isRunning()) {
                if (!startLongRunningRuntime()) {
                    persistSession();
                    return;
                }
            }
            while (isLongRunningMonitorActive()) {
                drainLongRunningRuntimeCompletions();
                if (!isLongRunningMonitorActive()) {
                    break;
                }
                if (longRunningMonitorInterruptRequested.get()) {
                    applyLongRunningMonitorInterrupt();
                    break;
                }
                screen.setLiveStatus(longRunningMonitorLines(false));
                try {
                    Optional<TerminalKeys.KeyPress> key = TerminalKeys.pollKey(terminal.reader(), 150);
                    if (key.isPresent()) {
                        TerminalKeys.Key pressed = key.get().key();
                        if (pressed == TerminalKeys.Key.ESCAPE || pressed == TerminalKeys.Key.CTRL_C) {
                            requestLongRunningMonitorInterrupt();
                            continue;
                        }
                        if (pressed == TerminalKeys.Key.EOF) {
                            break;
                        }
                    }
                } catch (IOException exception) {
                    screen.scrollback(Tk.errorTag("monitor") + " " + exception.getMessage());
                    requestLongRunningMonitorInterrupt();
                    break;
                }
            }
            while (longRunningRuntime != null && longRunningRuntime.isRunning()) {
                drainLongRunningRuntimeCompletions();
                screen.setLiveStatus(longRunningMonitorLines(true));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            drainLongRunningRuntimeCompletions();
        } finally {
            if (sigintRegistration != null) {
                sigintRegistration.close();
            }
            if (previousAttributes != null) {
                terminal.setAttributes(previousAttributes);
            }
            screen.clearLiveStatus();
            screen.setCursorVisible(true);
            if (interruptController != null) {
                interruptController.resume();
            }
            jlineScreen.enterIdlePhase();
        }
    }

    private List<String> longRunningMonitorLines(boolean interrupting) {
        List<String> lines = new ArrayList<>(longRunningMonitorRenderer.render(longRunningMonitorReader.read(
                session.workingDirectory(),
                session.longRunningTaskId(),
                interrupting)));
        lines.add("");
        lines.add(Tk.dim(longRunningStatusSpinner.tick() + " long task runing..."));
        return lines;
    }

    private void requestLongRunningMonitorInterrupt() {
        if (longRunningMonitorInterruptRequested.compareAndSet(false, true)) {
            recordLongRunningControllerEvent("user_requested_worker_runtime_interrupt",
                    Map.of("input", "ESC_or_CTRL_C"));
        }
    }

    private void applyLongRunningMonitorInterrupt() {
        screen.setLiveStatus(longRunningMonitorLines(true));
        if (longRunningRuntime != null && longRunningRuntime.isRunning()) {
            recordLongRunningControllerEvent("worker_runtime_interrupt_sent",
                    Map.of("reason", "user_interrupted"));
            longRunningRuntime.interrupt("user_interrupted");
        } else {
            recordLongRunningControllerEvent("worker_runtime_interrupt_skipped",
                    Map.of("reason", "runtime_not_running"));
        }
    }

    @Override
    protected void onSessionReplaced(ConversationSession newSession, boolean fresh) {
        if (currentSessionRef != null) {
            currentSessionRef.set(newSession);
        }
        sessionContext.batch(() -> {
            sessionContext.setCwd(newSession.workingDirectory());
            sessionContext.setSessionId(newSession.sessionId());
            sessionContext.syncFrom(newSession);
        });
        loadHistory();
    }

    private void loadHistory() {
        sessionHistory.reset(session.inputHistory());
    }

    private String buildPrompt() {
        return Tk.promptActive("❯") + " ";
    }

    @Override
    protected void renderSwitchedSessionHeader(ConversationSession newSession) {
        String title = newSession.title();
        String line = "Switched to session: " + newSession.sessionId();
        if (title != null && !title.equals("(empty session)") && !title.isBlank()) {
            line += " " + title;
        }
        BlockSpacing.scrollbackBlock(screen, Tk.dim(line));
    }

    private void replayRecentSession() {
        List<Message> messages = session.messages();
        if (messages.size() <= 50) {
            historyPrinter.printAll(messages);
            return;
        }
        int omitted = messages.size() - 20;
        screen.scrollback(Tk.dim("[" + omitted + " earlier messages omitted, use /replay-all to show]"));
        historyPrinter.printFrom(messages, omitted);
    }

    private static void runInlineBash(String command, Screen screen, Path cwd) throws IOException {
        try {
            BashShell.Result result = BashShell.execute(command, cwd);
            if (!result.stdout().isBlank()) {
                screen.scrollback(List.of(result.stdout().stripTrailing().split("\\R", -1)));
            }
            if (!result.stderr().isBlank()) {
                screen.scrollback(List.of(result.stderr().stripTrailing().split("\\R", -1)));
            }
            if (result.exitCode() != 0) {
                screen.scrollback(Tk.warnTag("exit") + " " + result.exitCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("bash interrupted", e);
        }
    }

    private static void appendInlineMemory(String text, Screen screen, Path cwd) throws IOException {
        if (text == null || text.isBlank()) {
            screen.scrollback(Tk.warnTag("memory") + " Nothing to save.");
            return;
        }
        Path target = memoryFileFor(cwd);
        Files.createDirectories(target.getParent());
        String entry = text.strip() + System.lineSeparator();
        Files.writeString(target, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        screen.scrollback(Tk.infoTag("memory") + " Saved to " + target);
    }

    static Path memoryFileFor(Path cwd) {
        String raw = cwd == null ? "default" : cwd.toAbsolutePath().normalize().toString();
        String project = raw.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (project.isBlank()) project = "default";
        return Path.of(System.getProperty("user.home"), ".mada", "projects",
                project, "memory", "MEMORY.md");
    }

    private static SlashContext.ModelChooser inlineModelChooser(
            JLineScreen screen, Terminal terminal) {
        return model -> chooseFromModel(screen, terminal, model);
    }

    private static SlashContext.ModeChooser inlineModeChooser(
            JLineScreen screen, Terminal terminal) {
        return model -> chooseFromModel(screen, terminal, model);
    }

    private static SlashContext.PermissionChooser inlinePermissionChooser(
            JLineScreen screen, Terminal terminal) {
        return model -> chooseFromModel(screen, terminal, model);
    }

    private static SlashContext.ThemeChooser inlineThemeChooser(
            JLineScreen screen, Terminal terminal) {
        return model -> chooseFromModel(screen, terminal, model);
    }

    private static SlashContext.ProviderChooser inlineProviderChooser(
            JLineScreen screen, Terminal terminal) {
        return model -> chooseFromModel(screen, terminal, model);
    }

    private static SessionChooser inlineSessionChooser(
            SessionStorage storage, JLineScreen screen, Terminal terminal) {
        return (sessions, currentSessionId) -> {
            if (sessions.isEmpty()) return Optional.empty();
            ChoicePrompt.Model<String> model = SessionSelectModels.resumeModel(
                    storage, sessions, currentSessionId);
            try {
                return new InlineChoicePrompt<String>(screen, terminal, null).choose(model);
            } catch (IOException e) {
                return Optional.empty();
            }
        };
    }

    private static Optional<String> chooseFromModel(
            JLineScreen screen, Terminal terminal,
            ChoicePrompt.Model<String> model) {
        try {
            return new InlineChoicePrompt<String>(screen, terminal, null).choose(model);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
