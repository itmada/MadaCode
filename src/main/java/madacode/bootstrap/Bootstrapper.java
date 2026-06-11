package madacode.bootstrap;

import madacode.cli.CliArgs;
import madacode.cli.Repl;
import madacode.events.AppEventPublisher;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.permission.PermissionGate;
import madacode.permission.PermissionMode;

/**
 * Composes the application object graph without a DI framework.
 *
 * <p>The bootstrap package is the application's composition root: all global
 * event installation, lifecycle ownership, and subsystem wiring live here so
 * runtime code can stay focused on behavior rather than startup mechanics.
 */
public final class Bootstrapper {

    private final CliArgs args;

    public Bootstrapper(CliArgs args) {
        this.args = args;
    }

    /**
     * Assembles the full object graph and returns a ready-to-{@code run()} REPL.
     */
    public Repl createRepl() {
        BootstrapResources resources = new BootstrapResources();
        try {
            TerminalRuntime terminal = TerminalAssembly.create(resources);
            var paths = EnvironmentAssembly.pathsForCurrentProject();
            EnvironmentAssembly.configureEarlyLogPaths(paths);
            EventsRuntime events = EventsAssembly.install(paths, terminal, resources);
            AppEventPublisher appEvents = events.publisher();
            terminal.interrupts().appEvents(appEvents);
            EnvironmentRuntime environment = EnvironmentAssembly.create(args, terminal, paths, appEvents);
            PermissionGate permission = PermissionAssembly.create(environment, terminal, appEvents);
            ToolRuntime tools = ToolAssembly.create(environment, resources, permission, appEvents);
            EngineRuntime engine = EngineAssembly.create(environment, tools, permission);
            SessionRuntime session = SessionAssembly.resolve(environment, terminal, appEvents);

            // Apply dangerously-bypass-permissions flag if set
            if (args.dangerouslyBypassPermissions()) {
                session.session().setPermissionMode(PermissionMode.BYPASS);
                appEvents.publish(UserVisibleEvent.error(
                        EventContext.bootstrap("Permission"),
                        "Warning: Bypass permission mode active — interactive approval suppressed. Structural safety rules (dangerous bash commands) still apply."));
            }

            events.foreground().setInitial(session.session());
            InteractionRuntime interaction = InteractionAssembly.create(
                    environment, terminal, tools, engine, session, resources, appEvents);
            Repl repl = ReplAssembly.create(
                    environment, terminal, engine, session, interaction);

            events.foreground().attach(repl::foregroundSessionId);
            resources.transferTo(repl);
            resources.installShutdownHook();
            return repl;
        } catch (RuntimeException | Error e) {
            resources.close();
            throw e;
        }
    }
}
