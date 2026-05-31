package madacode.bootstrap;

import madacode.cli.JLineRepl;
import madacode.cli.Repl;

final class ReplAssembly {

    private ReplAssembly() {
    }

    static Repl create(
            EnvironmentRuntime environment,
            TerminalRuntime terminal,
            EngineRuntime engine,
            SessionRuntime session,
            InteractionRuntime interaction) {
        Repl repl = JLineRepl.create(
                engine.engine(),
                interaction.turnExecutor(),
                session.session(),
                session.storage(),
                terminal.terminal(),
                terminal.screen(),
                interaction.lineReader(),
                interaction.sessionHistory(),
                interaction.slashRegistry(),
                environment.providerRegistry(),
                engine.compaction(),
                terminal.interrupts());
        terminal.approval().setTurnRenderer(repl.turnRenderer());
        return repl;
    }
}
