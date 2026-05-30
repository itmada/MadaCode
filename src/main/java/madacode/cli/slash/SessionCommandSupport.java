package madacode.cli.slash;

import madacode.core.session.ConversationSession;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.core.session.SessionStorageException;

import java.util.List;
import java.util.Optional;

final class SessionCommandSupport {

    private SessionCommandSupport() {}

    static Optional<ConversationSession> resolveById(SlashContext ctx, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<ConversationSession> exact;
        try {
            exact = ctx.storage().loadIfExists(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (exact.isPresent()) {
            return exact;
        }
        if (id.length() < 4) {
            return Optional.empty();
        }
        List<SessionSummary> matches;
        try {
            matches = ctx.storage().listSessions().stream()
                    .filter(s -> s.sessionId().startsWith(id))
                    .toList();
        } catch (SessionStorageException e) {
            return Optional.empty();
        }
        if (matches.size() == 1) {
            return ctx.storage().loadIfExists(matches.getFirst().sessionId());
        }
        if (matches.size() > 1) {
            ctx.screen().scrollback("Multiple sessions match '" + id + "':");
            for (SessionSummary s : matches) {
                ctx.screen().scrollback("  " + s.sessionId());
            }
        }
        return Optional.empty();
    }
}
