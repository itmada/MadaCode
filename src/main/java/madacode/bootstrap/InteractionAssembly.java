package madacode.bootstrap;

import madacode.cli.JLinePromptChannel;
import madacode.cli.LineReaderFactory;
import madacode.cli.UserPromptChannel;
import madacode.cli.editor.SessionHistory;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.core.engine.QueryEngineTurnRunner;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnLog;
import madacode.events.AppEventPublisher;
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
            BootstrapResources resources,
            AppEventPublisher publisher) {
        SlashCommandRegistry slashRegistry = SlashCommandRegistry.create(
                tools.skillRegistry());

        UserPromptChannel channel;
        LineReader lineReader = null;
        SessionHistory sessionHistory = null;

        sessionHistory = new SessionHistory();
        lineReader = LineReaderFactory.create(
                terminal.terminal(), slashRegistry, sessionHistory,
                session.session().workingDirectory());
        channel = new JLinePromptChannel(terminal.screen(), terminal.terminal(),
                terminal.interrupts(), terminal.interrupts()::interrupt);

        var turnRunner = new QueryEngineTurnRunner(engine.engine(), channel);
        var turnLog = new TurnLog(environment.paths().workspaceSessionsDir());
        var turnExecutor = resources.own(new TurnExecutor(turnRunner, turnLog));
        recoverUnfinishedTurns(turnExecutor, session, publisher);

        return new InteractionRuntime(
                slashRegistry,
                channel,
                lineReader,
                sessionHistory,
                turnExecutor);
    }

    private static void recoverUnfinishedTurns(
            TurnExecutor turnExecutor,
            SessionRuntime session,
            AppEventPublisher publisher) {
        List<String> unfinished = turnExecutor.recoverOnStartup();
        if (!unfinished.isEmpty()) {
            publisher.publish(DiagnosticEvent.info(
                    EventContext.of(session.session(), "Bootstrapper"),
                    "Recovered " + unfinished.size()
                            + " unfinished turn(s) from previous run, marked as FAILED"));
        }
    }
}
