package madacode.cli;

import madacode.cli.editor.SessionHistory;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.engine.QueryEngine;
import madacode.core.session.SessionStorage;
import madacode.core.turn.TurnExecutor;
import madacode.services.compact.CompactPlanner;
import madacode.provider.ProviderRegistry;
import madacode.render.ExpandableHistory;
import madacode.render.BlockSpacing;
import madacode.render.UserInputRenderer;
import madacode.render.turn.TurnRenderer;
import madacode.render.turn.TurnView;
import madacode.cli.session.SessionChooser;
import madacode.cli.session.SessionSelectModels;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.cli.slash.SlashComposer;
import madacode.cli.slash.SlashContext;
import madacode.tui.JLineScreen;
import madacode.tui.Screen;
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
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

public final class JLineRepl extends Repl {

    private final Terminal terminal;
    private final JLineScreen jlineScreen;
    private final LineReader lineReader;
    private final SessionHistory sessionHistory;
    private final SlashComposer slashComposer;

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

        // Slash compose: triggered when buffer contains only "/"
        SlashContext slashCtx = new SlashContext(
                session, screen, sessionStorage, slashRegistry, queryEngine, providerRegistry,
                compactPlanner, ctx, Optional.ofNullable(sessionChooser),
                Optional.of(modelChooser), Optional.of(modeChooser), Optional.of(permissionChooser),
                Optional.of(themeChooser), Optional.of(providerChooser));
        SlashComposer slashComposer = new SlashComposer(
                slashRegistry, slashCtx, screen, screen, terminal);

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

        JLineRepl repl = new JLineRepl(config, terminal, screen, lineReader, sessionHistory, slashComposer);
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
                loadHistory();
            }
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

    @Override
    protected void onSessionReplaced(ConversationSession newSession, boolean fresh) {
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
        return models -> chooseFromList(screen, terminal,
                "Model", "", models);
    }

    private static SlashContext.ModeChooser inlineModeChooser(
            JLineScreen screen, Terminal terminal) {
        return modes -> chooseFromList(screen, terminal,
                "Mode", "", modes);
    }

    private static SlashContext.PermissionChooser inlinePermissionChooser(
            JLineScreen screen, Terminal terminal) {
        return permissions -> chooseFromList(screen, terminal,
                "Permission", "", permissions);
    }

    private static SlashContext.ThemeChooser inlineThemeChooser(
            JLineScreen screen, Terminal terminal) {
        return themes -> chooseFromList(screen, terminal,
                "Theme", "", themes);
    }

    private static SlashContext.ProviderChooser inlineProviderChooser(
            JLineScreen screen, Terminal terminal) {
        return providers -> chooseFromList(screen, terminal,
                "Provider", "", providers);
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

    private static Optional<String> chooseFromList(
            JLineScreen screen, Terminal terminal,
            String title, String subtitle, List<String> items) {
        List<ChoicePrompt.Option<String>> options = items.stream()
                .map(s -> new ChoicePrompt.Option<>(s, s, "", ""))
                .toList();
        try {
            return new InlineChoicePrompt<String>(screen, terminal, null).choose(
                    new ChoicePrompt.Model<>(title, subtitle, options,
                            "↑/↓ select   Enter confirm   Esc cancel", 0));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
