package madacode.cli.session;

import madacode.core.session.ConversationSession;
import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.core.session.SessionStorageException;
import madacode.tui.TerminalText;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class SessionChoiceFormatter {

    private static final DateTimeFormatter META_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private SessionChoiceFormatter() {}

    static String title(SessionStorage storage, SessionSummary summary) {
        try {
            ConversationSession session = storage.load(summary.sessionId());
            return TerminalText.truncateMiddle(session.title(), 64);
        } catch (SessionStorageException e) {
            return summary.messageCount() + " messages";
        }
    }

    static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    static String metadata(SessionSummary summary) {
        return META_FMT.format(summary.lastModifiedAt())
                + "  "
                + shortId(summary.sessionId())
                + "  "
                + summary.messageCount()
                + " msg";
    }

    static String choiceLabel(String action, SessionStorage storage, SessionSummary summary) {
        return action + "  " + title(storage, summary) + "  " + metadata(summary);
    }

    static String plainLabel(SessionStorage storage, SessionSummary summary) {
        return title(storage, summary) + "  " + metadata(summary);
    }
}
