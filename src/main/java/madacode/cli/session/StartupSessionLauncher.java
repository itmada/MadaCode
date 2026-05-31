package madacode.cli.session;

import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.tui.widget.ChoicePrompt;
import madacode.tui.widget.ChoicePrompter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class StartupSessionLauncher {

    private static final int MAX_RECENT = 4;

    private final SessionStorage storage;
    private final ChoicePrompter<Choice> prompt;

    public StartupSessionLauncher(SessionStorage storage, ChoicePrompter<Choice> prompt) {
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.prompt = java.util.Objects.requireNonNull(prompt, "prompt");
    }

    public Choice choose() {
        List<SessionSummary> recent = SessionListings.recent(storage, MAX_RECENT);
        if (recent.isEmpty()) {
            return new Choice.NewSession();
        }
        List<ChoicePrompt.Option<Choice>> options = new ArrayList<>();
        for (SessionSummary summary : recent) {
            options.add(new ChoicePrompt.Option<>(
                    new Choice.Resume(summary.sessionId()),
                    "Continue",
                    title(summary),
                    SessionSelectModels.meta(summary)));
        }
        options.add(new ChoicePrompt.Option<>(
                new Choice.NewSession(),
                "New session",
                "Start fresh in this workspace",
                ""));
        options.add(new ChoicePrompt.Option<>(
                new Choice.Exit(),
                "Exit",
                "Leave without opening a session",
                ""));

        String subtitle = recent.isEmpty()
                ? "Start a workspace session"
                : "Pick up where you left off";
        ChoicePrompt.Model<Choice> model = new ChoicePrompt.Model<>(
                "Mada",
                subtitle,
                options,
                "↑/↓ select   Enter confirm   Esc exit",
                0);
        try {
            Optional<Choice> choice = prompt.choose(model);
            return choice.orElseGet(Choice.Exit::new);
        } catch (Exception e) {
            return new Choice.NewSession();
        }
    }

    private String title(SessionSummary summary) {
        return SessionSelectModels.title(storage, summary);
    }

    public sealed interface Choice {
        record Resume(String sessionId) implements Choice {}
        record NewSession() implements Choice {}
        record Exit() implements Choice {}
    }
}
