package madacode.cli;

import madacode.cli.slash.SlashCommandRegistry;
import madacode.core.engine.QueryEngine;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.core.session.SessionStorage;
import madacode.core.turn.TurnExecutor;
import madacode.provider.ProviderRegistry;
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
        super(buildConfig(queryEngine, turnExecutor, session, output, sessionStorage,
                slashRegistry, providerRegistry, compactPlanner));
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    private static Config buildConfig(QueryEngine queryEngine,
                                      TurnExecutor turnExecutor,
                                      ConversationSession session,
                                      PrintStream output,
                                      SessionStorage sessionStorage,
                                      SlashCommandRegistry slashRegistry,
                                      ProviderRegistry providerRegistry,
                                      CompactPlanner compactPlanner) {
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
        config.sessionContext.setMode(SessionMode.from(session));
        return config;
    }

    @Override
    public void run() {
        try {
            persistSession();
            while (true) {
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
            persistSession();
        } finally {
            closeResources();
        }
    }
}
