package madacode.bootstrap;

import madacode.cli.CliArgs;
import madacode.cli.session.SessionPointer;
import madacode.cli.session.StartupSessionLauncher;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.tui.WelcomeCard;
import madacode.tui.inline.InlineChoicePrompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class SessionAssembly {

    private SessionAssembly() {
    }

    static SessionRuntime resolve(EnvironmentRuntime environment, TerminalRuntime terminal) {
        SessionStorage storage = SessionStorage.defaultStorage();

        if (environment.args() instanceof CliArgs.Interactive) {
            return new SessionRuntime(storage,
                    resolveStartupSession(environment, storage, terminal));
        }
        return new SessionRuntime(storage,
                resolveSession(environment.args(), storage));
    }

    private static ConversationSession resolveSession(
            CliArgs args, SessionStorage storage) {
        return switch (args) {
            case CliArgs.NewSession n -> {
                ConversationSession session = new ConversationSession();
                SessionPointer.write(session.sessionId());
                yield session;
            }
            case CliArgs.Resume r -> {
                Optional<ConversationSession> found =
                        resolveById(r.sessionId(), storage);
                if (found.isEmpty()) {
                    throw new BootstrapException(
                            "No session found matching: " + r.sessionId(), 1);
                }
                ConversationSession session = found.get();
                SessionPointer.write(session.sessionId());
                yield session;
            }
            case CliArgs.Continue c -> {
                Optional<ConversationSession> byPointer =
                        SessionPointer.read().flatMap(storage::loadIfExists);
                if (byPointer.isPresent()) {
                    yield byPointer.get();
                }
                Optional<SessionSummary> recent = storage.findMostRecent();
                if (recent.isPresent()) {
                    AppEvents.publisher().publish(UserVisibleEvent.info(
                            EventContext.bootstrap("Session"),
                            "(no previous session pointer — loading most recent)"));
                    ConversationSession session = storage.load(
                            recent.get().sessionId());
                    SessionPointer.write(session.sessionId());
                    yield session;
                }
                AppEvents.publisher().publish(UserVisibleEvent.info(
                        EventContext.bootstrap("Session"),
                        "(no previous session — starting new)"));
                ConversationSession session = new ConversationSession();
                SessionPointer.write(session.sessionId());
                yield session;
            }
            case CliArgs.Interactive i -> {
                ConversationSession session = new ConversationSession();
                SessionPointer.write(session.sessionId());
                yield session;
            }
            case CliArgs.ListSessions l -> throw new IllegalStateException("LIST/HELP handled in main()");
            case CliArgs.Help h -> throw new IllegalStateException("LIST/HELP handled in main()");
        };
    }

    private static ConversationSession resolveStartupSession(
            EnvironmentRuntime environment,
            SessionStorage storage,
            TerminalRuntime terminal) {
        var active = environment.providerRegistry().active();
        String provider = active.provider().name();
        String model = active.currentModel().name();
        Path cwd = environment.projectDir();
        terminal.screen().scrollback("");
        terminal.screen().scrollback(
                WelcomeCard.render(provider, model, cwd, terminal.screen().width()));
        StartupSessionLauncher launcher = new StartupSessionLauncher(
                storage,
                new InlineChoicePrompt<StartupSessionLauncher.Choice>(
                        terminal.screen(), terminal.terminal(), null));
        StartupSessionLauncher.Choice choice = launcher.choose();
        return switch (choice) {
            case StartupSessionLauncher.Choice.Resume r -> {
                ConversationSession session = storage.load(r.sessionId());
                SessionPointer.write(session.sessionId());
                yield session;
            }
            case StartupSessionLauncher.Choice.NewSession n -> {
                ConversationSession session = new ConversationSession();
                SessionPointer.write(session.sessionId());
                yield session;
            }
            case StartupSessionLauncher.Choice.Exit e -> {
                throw new BootstrapException("exit", 0);
            }
        };
    }

    private static Optional<ConversationSession> resolveById(
            String id, SessionStorage storage) {
        Optional<ConversationSession> exact;
        try {
            exact = storage.loadIfExists(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (exact.isPresent()) {
            return exact;
        }

        if (id.length() >= 4) {
            List<SessionSummary> matches = storage.listSessions().stream()
                    .filter(s -> s.sessionId().startsWith(id))
                    .toList();
            if (matches.size() == 1) {
                return storage.loadIfExists(matches.getFirst().sessionId());
            }
            if (matches.size() > 1) {
                var sb = new StringBuilder(
                        "Multiple sessions match '" + id + "':");
                matches.forEach(s ->
                        sb.append("\n  ").append(s.sessionId()));
                throw new BootstrapException(sb.toString(), 1);
            }
        }
        return Optional.empty();
    }
}
