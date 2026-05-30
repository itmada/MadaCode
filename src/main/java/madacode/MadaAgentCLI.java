package madacode;

import madacode.bootstrap.BootstrapException;
import madacode.bootstrap.Bootstrapper;
import madacode.cli.CliArgs;
import madacode.provider.ProviderException;
import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.core.session.SessionStorageException;
import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.events.FatalEvent;
import madacode.events.UserVisibleEvent;

import java.util.List;

/**
 * Application entry point. Parses CLI args, then delegates to
 * {@link Bootstrapper} which assembles the full object graph.
 */
public final class MadaAgentCLI {

    private MadaAgentCLI() {}

    public static void main(String[] args) {
        CliArgs cli;
        try {
            cli = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            AppEvents.publisher().publish(FatalEvent.create(
                    EventContext.bootstrap("CLI"), e.getMessage(), null, 2));
            System.exit(2);
            return;
        }

        switch (cli) {
            case CliArgs.Help h -> printUsage();
            case CliArgs.ListSessions l -> printSessionList(SessionStorage.defaultStorage());
            default -> {
                try {
                    new Bootstrapper(cli).createRepl().run();
                    // JLine/jansi keep non-daemon threads (terminal reader, signal
                    // handlers) alive after the REPL returns; explicit exit so the
                    // process terminates instead of hanging.
                    System.exit(0);
                } catch (BootstrapException e) {
                    if (e.exitCode() != 0) {
                        AppEvents.publisher().publish(UserVisibleEvent.error(
                                EventContext.bootstrap("Bootstrap"), e.getMessage(), e));
                    } else if (e.getMessage() != null) {
                        // Friendly early-exit (e.g. first-time providers.json template
                        // created). Use stdout directly — event bus may not be ready
                        // for clean info output at this point.
                        System.out.println(e.getMessage());
                    }
                    System.exit(e.exitCode());
                } catch (ProviderException e) {
                    AppEvents.publisher().publish(UserVisibleEvent.error(
                            EventContext.bootstrap("Provider"),
                            "Provider configuration error: " + e.getMessage(), e));
                    System.exit(1);
                } catch (SessionStorageException e) {
                    AppEvents.publisher().publish(UserVisibleEvent.error(
                            EventContext.bootstrap("SessionStorage"),
                            "Session error: " + e.getMessage(), e));
                    System.exit(1);
                } catch (RuntimeException e) {
                    // Last-resort bucket for anything thrown outside a turn
                    // (init, shutdown, listener fanout during session replay, etc.)
                    // so the user sees a single line instead of a raw JVM stack trace.
                    AppEvents.publisher().publish(FatalEvent.create(
                            EventContext.bootstrap("CLI"),
                            "Fatal: " + e.getClass().getSimpleName()
                                    + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                            e,
                            1));
                    System.exit(1);
                }
            }
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: mada [options]
                Options:
                  (no args)           Interactive startup selector
                  --new               Start a new session
                  --continue, -c      Continue the most recent session
                  --resume <id>, -r   Resume a specific session by ID
                  --list, -l          List all saved sessions
                  --help, -h          Show this help
                Flags:
                  --no-memory         Disable memory (project context)
                  --dangerously-bypass-permissions
                                      Skip approval prompts (DANGEROUS — use only in
                                      trusted automation contexts; safety rules still apply)
                  --provider <name>    Start with a specific provider from providers.json
                Environment:
                  MADA_NO_PICKER=true  Skip interactive selector, start new session""");
    }

    private static void printSessionList(SessionStorage storage) {
        try {
            List<SessionSummary> sessions = storage.listSessions();
            if (sessions.isEmpty()) {
                System.out.println("No saved sessions.");
                return;
            }
            System.out.println("Sessions:");
            System.out.printf("  %-38s  %-20s  %-10s  %-50s%n",
                    "ID", "Created", "Messages", "Path");
            for (SessionSummary s : sessions) {
                System.out.printf("  %-38s  %-20s  %-10d  %-50s%n",
                        s.sessionId(),
                        s.createdAt().toString().substring(0, 19),
                        s.messageCount(),
                        s.workingDirectory());
            }
        } catch (SessionStorageException e) {
            AppEvents.publisher().publish(UserVisibleEvent.error(
                    EventContext.bootstrap("SessionStorage"),
                    "Failed to list sessions: " + e.getMessage(), e));
        }
    }
}
