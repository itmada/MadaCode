package madacode.cli.session;

import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.tui.widget.ChoicePrompt;

import java.util.ArrayList;
import java.util.List;

public final class SessionSelectModels {

    private SessionSelectModels() {}

    public static ChoicePrompt.Model<String> resumeModel(
            SessionStorage storage,
            List<SessionSummary> sessions,
            String currentSessionId) {
        List<ChoicePrompt.Option<String>> options = new ArrayList<>();
        for (SessionSummary summary : sessions) {
            boolean current = summary.sessionId().equals(currentSessionId);
            String action = current ? "Current" : "Resume";
            String primary = SessionChoiceFormatter.choiceLabel(action, storage, summary);
            options.add(new ChoicePrompt.Option<>(
                    summary.sessionId(), primary, "", SessionChoiceFormatter.metadata(summary)));
        }
        return new ChoicePrompt.Model<>(
                "Session",
                "",
                options,
                "↑/↓ select   Enter resume   Esc cancel",
                0);
    }

    public static String title(SessionStorage storage, SessionSummary summary) {
        return SessionChoiceFormatter.title(storage, summary);
    }

    public static String shortId(String id) {
        return SessionChoiceFormatter.shortId(id);
    }

    public static String meta(SessionSummary summary) {
        return SessionChoiceFormatter.metadata(summary);
    }
}
