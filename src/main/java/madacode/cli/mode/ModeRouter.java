package madacode.cli.mode;

import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;

import java.util.Objects;

/**
 * Routes plain user input to the handler for the active workflow mode.
 */
public final class ModeRouter {

    private final ModeHandler commonHandler;
    private final ModeHandler longRunningHandler;

    public ModeRouter(ModeHandler commonHandler, ModeHandler longRunningHandler) {
        this.commonHandler = Objects.requireNonNull(commonHandler, "commonHandler");
        this.longRunningHandler = Objects.requireNonNull(longRunningHandler, "longRunningHandler");
    }

    public ModeExecution handle(String line, ConversationSession session) {
        return handlerFor(session).handle(line, session);
    }

    public ModeHandler handlerFor(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        return session.workflowMode() == SessionMode.LONG_RUNNING
                ? longRunningHandler
                : commonHandler;
    }
}
