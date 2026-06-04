package madacode.cli;

import madacode.cli.mode.ModeRouter;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.core.engine.QueryEngine;
import madacode.core.session.ConversationSession;
import madacode.core.turn.TurnExecutor;
import madacode.core.session.SessionStorage;
import madacode.provider.ProviderRegistry;
import madacode.longrunning.LongRunningLauncher;
import madacode.longrunning.LongRunningRuntime;
import madacode.permission.PermissionGate;
import madacode.render.turn.TurnRenderer;
import madacode.render.turn.TurnView;
import madacode.services.compact.CompactPlanner;
import madacode.tui.TextScreen;
import madacode.tui.widget.SessionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

final class ScriptedRepl extends Repl {

    private final BufferedReader reader;

    ScriptedRepl(QueryEngine queryEngine,
                 TurnExecutor turnExecutor,
                 ConversationSession session,
                 BufferedReader reader,
                 PrintStream output,
                 SessionStorage sessionStorage) {
        this(queryEngine, turnExecutor, session, reader, output, sessionStorage,
                SlashCommandRegistry.create(null), null, null);
    }

    ScriptedRepl(QueryEngine queryEngine,
                 TurnExecutor turnExecutor,
                 ConversationSession session,
                 BufferedReader reader,
                 PrintStream output,
                 SessionStorage sessionStorage,
                 SlashCommandRegistry slashRegistry,
                 ProviderRegistry providerRegistry,
                 CompactPlanner compactPlanner) {
        this(queryEngine, turnExecutor, session, reader, output, sessionStorage,
                slashRegistry, providerRegistry, compactPlanner, null);
    }

    ScriptedRepl(QueryEngine queryEngine,
                 TurnExecutor turnExecutor,
                 ConversationSession session,
                 BufferedReader reader,
                 PrintStream output,
                 SessionStorage sessionStorage,
                 SlashCommandRegistry slashRegistry,
                 ProviderRegistry providerRegistry,
                 CompactPlanner compactPlanner,
                 ModeRouter modeRouter) {
        super(buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                slashRegistry, providerRegistry, compactPlanner, modeRouter, null));
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    static ScriptedRepl withoutLongRunningRuntime(QueryEngine queryEngine,
                                                  TurnExecutor turnExecutor,
                                                  ConversationSession session,
                                                  BufferedReader reader,
                                                  PrintStream output,
                                                  SessionStorage sessionStorage) {
        return new ScriptedRepl(buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                SlashCommandRegistry.create(null), null, null, null, null, null,
                null, null, false), reader);
    }

    private ScriptedRepl(Config config, BufferedReader reader) {
        super(config);
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    ScriptedRepl(QueryEngine queryEngine,
                 TurnExecutor turnExecutor,
                 ConversationSession session,
                 BufferedReader reader,
                 PrintStream output,
                 SessionStorage sessionStorage,
                 LongRunningLauncher launcher,
                 PermissionGate permissionGate) {
        super(buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                SlashCommandRegistry.create(null), null, null, null, null, null,
                launcher, permissionGate, true));
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    ScriptedRepl(QueryEngine queryEngine,
                 TurnExecutor turnExecutor,
                 ConversationSession session,
                 BufferedReader reader,
                 PrintStream output,
                 SessionStorage sessionStorage,
                 LongRunningRuntime longRunningRuntime) {
        super(buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                SlashCommandRegistry.create(null), null, null, null, longRunningRuntime, null));
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    ScriptedRepl(QueryEngine queryEngine,
                 TurnExecutor turnExecutor,
                 ConversationSession session,
                 BufferedReader reader,
                 PrintStream output,
                 SessionStorage sessionStorage,
                 LongRunningRuntime longRunningRuntime,
                 UserPromptChannel promptChannel) {
        super(buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                SlashCommandRegistry.create(null), null, null, null, longRunningRuntime, promptChannel));
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    private static Config buildConfig(QueryEngine queryEngine,
                                      TurnExecutor turnExecutor,
                                      ConversationSession session,
                                      PrintStream output,
                                      SessionStorage sessionStorage,
                                      SlashCommandRegistry slashRegistry,
                                      ProviderRegistry providerRegistry,
                                      CompactPlanner compactPlanner,
                                      ModeRouter modeRouter,
                                      LongRunningRuntime longRunningRuntime) {
        return buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                slashRegistry, providerRegistry, compactPlanner, modeRouter, longRunningRuntime, null,
                null, null, true);
    }

    private static Config buildConfig(QueryEngine queryEngine,
                                      TurnExecutor turnExecutor,
                                      ConversationSession session,
                                      PrintStream output,
                                      SessionStorage sessionStorage,
                                      SlashCommandRegistry slashRegistry,
                                      ProviderRegistry providerRegistry,
                                      CompactPlanner compactPlanner,
                                      ModeRouter modeRouter,
                                      LongRunningRuntime longRunningRuntime,
                                      UserPromptChannel promptChannel) {
        return buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                slashRegistry, providerRegistry, compactPlanner, modeRouter, longRunningRuntime, promptChannel,
                null, null, true);
    }

    private static Config buildConfig(QueryEngine queryEngine,
                                      TurnExecutor turnExecutor,
                                      ConversationSession session,
                                      PrintStream output,
                                      SessionStorage sessionStorage,
                                      SlashCommandRegistry slashRegistry,
                                      ProviderRegistry providerRegistry,
                                      CompactPlanner compactPlanner,
                                      ModeRouter modeRouter,
                                      LongRunningRuntime longRunningRuntime,
                                      UserPromptChannel promptChannel,
                                      LongRunningLauncher launcher,
                                      PermissionGate permissionGate,
                                      boolean defaultPermissionGate) {
        TextScreen screen = new TextScreen(Objects.requireNonNull(output, "output"));
        TurnView turnView = new TurnView(screen);
        Config config = new Config();
        config.queryEngine = queryEngine;
        config.turnExecutor = turnExecutor;
        config.session = session;
        config.screen = screen;
        config.sessionStorage = sessionStorage;
        config.slashRegistry = slashRegistry;
        config.turnRenderer = new TurnRenderer(turnView, screen);
        config.providerRegistry = providerRegistry;
        config.compactPlanner = compactPlanner;
        config.sessionContext = new SessionContext();
        config.sessionContext.syncFrom(session);
        config.modeRouter = modeRouter;
        config.launcher = launcher;
        config.longRunningRuntime = longRunningRuntime;
        config.promptChannel = promptChannel;
        config.permissionGate = permissionGate == null && defaultPermissionGate
                ? PermissionGate.permissive()
                : permissionGate;
        config.workerTurnLogRoot = sessionStorage.transcriptPath(session.sessionId()).getParent();
        return config;
    }

    @Override
    public void run() {
        try {
            persistSession();
            while (true) {
                drainLongRunningRuntimeCompletions();
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    screen.scrollback("Failed to read input: " + e.getMessage());
                    return;
                }
                if (line == null || isExitCommand(line)) break;
                if (line.isBlank()) continue;
                if (!handleLine(line)) return;
            }
            drainLongRunningRuntimeCompletions();
            persistSession();
        } finally {
            closeResources();
        }
    }

    boolean startLongRunningRuntimeForTest() {
        return startLongRunningRuntime();
    }
}
