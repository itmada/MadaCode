package madacode.events;

import madacode.core.session.ConversationSession;

import java.util.Objects;

public record EventContext(
        String sessionId,
        String parentSessionId,
        String turnId,
        String source) {

    public EventContext {
        source = requireSource(source);
    }

    public static EventContext bootstrap(String source) {
        return new EventContext(null, null, null, source);
    }

    public static EventContext of(ConversationSession session, String source) {
        Objects.requireNonNull(session, "session");
        return new EventContext(session.sessionId(), null, null, source);
    }

    public static EventContext of(ConversationSession session, String turnId, String source) {
        Objects.requireNonNull(session, "session");
        return new EventContext(session.sessionId(), null, turnId, source);
    }

    public EventContext withParentSessionId(String parentSessionId) {
        return new EventContext(sessionId, parentSessionId, turnId, source);
    }

    public EventContext withTurnId(String turnId) {
        return new EventContext(sessionId, parentSessionId, turnId, source);
    }

    private static String requireSource(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.strip();
    }
}
