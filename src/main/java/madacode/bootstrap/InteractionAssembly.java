package madacode.bootstrap;

import madacode.cli.HeadlessPromptChannel;
import madacode.cli.JLinePromptChannel;
import madacode.cli.LineReaderFactory;
import madacode.cli.UserPromptChannel;
import madacode.cli.editor.SessionHistory;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.core.QueryEngineTurnRunner;
import madacode.core.TurnExecutor;
import madacode.core.TurnLog;
import madacode.events.AppEvents;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;

import org.jline.reader.LineReader;

import java.util.List;

final class InteractionAssembly {

    private InteractionAssembly() {
    }

    static InteractionRuntime create(
            EnvironmentRuntime environment,
            TerminalRuntime terminal,
            ToolRuntime tools,
            EngineRuntime engine,
            SessionRuntime session,
            BootstrapResources resources) {
        SlashCommandRegistry slashRegistry = SlashCommandRegistry.create(
                tools.skillRegistry());

        UserPromptChannel channel;
        LineReader lineReader = null;
        SessionHistory sessionHistory = null;

        if (terminal.interactive()) {
            sessionHistory = new SessionHistory();
            lineReader = LineReaderFactory.create(
                    terminal.terminal(), slashRegistry, sessionHistory,
                    session.session().workingDirectory());
            channel = new JLinePromptChannel(terminal.screen(), terminal.terminal(),
                    terminal.interrupts(), terminal.interrupts()::interrupt);
        } else {
            channel = HeadlessPromptChannel.INSTANCE;
        }

        var turnRunner = new QueryEngineTurnRunner(engine.engine(), channel);
        var turnLog = new TurnLog(environment.homeDir().resolve(".mada/sessions"));
        var turnExecutor = resources.own(new TurnExecutor(turnRunner, turnLog));
        recoverUnfinishedTurns(turnExecutor, session);

        return new InteractionRuntime(
                slashRegistry,
                channel,
                lineReader,
                sessionHistory,
                turnExecutor);
    }

    private static void recoverUnfinishedTurns(
            TurnExecutor turnExecutor,
            SessionRuntime session) {
        List<String> unfinished = turnExecutor.recoverOnStartup();
        if (!unfinished.isEmpty()) {
            AppEvents.publisher().publish(DiagnosticEvent.info(
                    EventContext.of(session.session(), "Bootstrapper"),
                    "Recovered " + unfinished.size()
                            + " unfinished turn(s) from previous run, marked as FAILED"));
        }
    }
}
