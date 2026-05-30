package madacode.cli;

import madacode.core.ConversationSession;
import madacode.core.QueryEngine;
import madacode.core.SessionStorage;
import madacode.core.TurnExecutor;
import madacode.provider.ProviderRegistry;
import madacode.services.compact.CompactPlanner;
import madacode.render.turn.TurnRenderer;
import madacode.render.turn.TurnView;
import madacode.tui.TextScreen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Headless / non-TTY line-reader REPL. Used for scripting and CI pipelines
 * where no interactive terminal is available. Permission is always deny.
 */
public final class BufferedRepl extends Repl {

    private final BufferedReader reader;

    public BufferedRepl(QueryEngine queryEngine,
                        TurnExecutor turnExecutor,
                        ConversationSession session,
                        BufferedReader reader,
                        PrintStream output,
                        SessionStorage sessionStorage) {
        this(queryEngine, turnExecutor, session, reader, output, sessionStorage,
                madacode.cli.slash.SlashCommandRegistry.create(null), null, null);
    }

    public BufferedRepl(QueryEngine queryEngine,
                        TurnExecutor turnExecutor,
                        ConversationSession session,
                        BufferedReader reader,
                        PrintStream output,
                        SessionStorage sessionStorage,
                        madacode.cli.slash.SlashCommandRegistry slashRegistry,
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
                                      madacode.cli.slash.SlashCommandRegistry slashRegistry,
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
        config.expandableHistory = null;
        config.providerRegistry = providerRegistry;
        config.compactPlanner = compactPlanner;
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
