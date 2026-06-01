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
                    SessionChoiceFormatter.choiceLabel("Continue", storage, summary),
                    "",
                    SessionChoiceFormatter.metadata(summary)));
        }
        options.add(new ChoicePrompt.Option<>(
                new Choice.NewSession(),
                "New session",
                "",
                ""));
        options.add(new ChoicePrompt.Option<>(
                new Choice.Exit(),
                "Exit",
                "",
                ""));

        ChoicePrompt.Model<Choice> model = new ChoicePrompt.Model<>(
                "Mada",
                "",
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
    public sealed interface Choice {
        record Resume(String sessionId) implements Choice {}
        record NewSession() implements Choice {}
        record Exit() implements Choice {}
    }
}
