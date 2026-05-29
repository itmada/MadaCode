package madacode.bootstrap;

import madacode.cli.BufferedRepl;
import madacode.cli.JLineRepl;
import madacode.cli.Repl;
import madacode.provider.ProviderRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class ReplAssembly {

    private ReplAssembly() {
    }

    static Repl create(
            EnvironmentRuntime environment,
            TerminalRuntime terminal,
            EngineRuntime engine,
            SessionRuntime session,
            InteractionRuntime interaction) {
        if (!terminal.interactive()) {
            return new BufferedRepl(
                    engine.engine(),
                    interaction.turnExecutor(),
                    session.session(),
                    new BufferedReader(new InputStreamReader(
                            System.in, StandardCharsets.UTF_8)),
                    System.out,
                    session.storage(),
                    interaction.slashRegistry(),
                    environment.providerRegistry(),
                    engine.compaction());
        }

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
