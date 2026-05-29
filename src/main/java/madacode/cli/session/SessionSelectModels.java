package madacode.cli.session;

import madacode.core.ConversationSession;
import madacode.core.SessionStorage;
import madacode.core.SessionStorage.SessionSummary;
import madacode.core.SessionStorageException;
import madacode.tui.TerminalText;
import madacode.tui.widget.ChoicePrompt;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class SessionSelectModels {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private SessionSelectModels() {}

    public static ChoicePrompt.Model<String> resumeModel(
            SessionStorage storage,
            List<SessionSummary> sessions,
            String currentSessionId) {
        List<ChoicePrompt.Option<String>> options = new ArrayList<>();
        for (SessionSummary summary : sessions) {
            boolean current = summary.sessionId().equals(currentSessionId);
            String primary = current ? "Current" : "Resume";
            String secondary = title(storage, summary);
            options.add(new ChoicePrompt.Option<>(
                    summary.sessionId(), primary, secondary, meta(summary)));
        }
        return new ChoicePrompt.Model<>(
                "Resume session",
                sessions.isEmpty() ? "No saved sessions" : "Choose a saved conversation",
                options,
                "↑/↓ select   Enter resume   Esc cancel",
                0);
    }

    public static String title(SessionStorage storage, SessionSummary summary) {
        try {
            ConversationSession session = storage.load(summary.sessionId());
            return TerminalText.truncateMiddle(session.title(), 64);
        } catch (SessionStorageException e) {
            return summary.messageCount() + " messages";
        }
    }

    public static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    public static String meta(SessionSummary summary) {
        return DATE_FMT.format(summary.lastModifiedAt()) + "  " + shortId(summary.sessionId());
    }
}
