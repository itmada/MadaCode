package madacode.bootstrap;

import madacode.core.ConversationSession;

import java.util.Objects;
import java.util.function.Supplier;

final class ForegroundSessionTracker implements Supplier<String> {

    private volatile ConversationSession initialSession;
    private volatile Supplier<String> liveSessionId;

    void setInitial(ConversationSession session) {
        this.initialSession = Objects.requireNonNull(session, "session");
    }

    void attach(Supplier<String> liveSessionId) {
        this.liveSessionId = Objects.requireNonNull(liveSessionId, "liveSessionId");
    }

    @Override
    public String get() {
        Supplier<String> live = liveSessionId;
        if (live != null) {
            return live.get();
        }
        ConversationSession session = initialSession;
        return session == null ? null : session.sessionId();
    }
}
