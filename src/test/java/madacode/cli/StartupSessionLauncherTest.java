package madacode.cli;

import madacode.cli.session.SessionSelectModels;
import madacode.cli.session.StartupSessionLauncher;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.session.SessionStorage;
import madacode.tui.widget.ChoicePrompt;
import madacode.tui.widget.ChoicePrompter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StartupSessionLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void promptIsShownAndSelectsResume() {
        SessionStorage storage = storage();
        for (int i = 0; i < 2; i++) {
            storage.save(session("session-" + i, "question " + i));
        }
        AtomicReference<ChoicePrompt.Model<StartupSessionLauncher.Choice>> seen = new AtomicReference<>();
        ChoicePrompter<StartupSessionLauncher.Choice> prompter = model -> {
            seen.set(model);
            return Optional.of(model.options().getFirst().value()); // first = a Resume
        };

        StartupSessionLauncher.Choice choice =
                new StartupSessionLauncher(storage, prompter).choose();

        assertInstanceOf(StartupSessionLauncher.Choice.Resume.class, choice);
        ChoicePrompt.Model<StartupSessionLauncher.Choice> model = seen.get();
        assertNotNull(model, "prompt model must be presented");
        // Recent + New session + Exit
        assertEquals(2 + 2, model.options().size());
        assertTrue(model.options().stream()
                        .anyMatch(o -> o.value() instanceof StartupSessionLauncher.Choice.NewSession),
                "model must include New session option");
        assertTrue(model.options().stream()
                        .anyMatch(o -> o.value() instanceof StartupSessionLauncher.Choice.Exit),
                "model must include Exit option");
    }

    @Test
    void emptyHistoryAutoStartsNewSessionWithoutPrompting() {
        AtomicReference<Boolean> wasCalled = new AtomicReference<>(false);
        ChoicePrompter<StartupSessionLauncher.Choice> prompter = model -> {
            wasCalled.set(true);
            return Optional.empty();
        };

        StartupSessionLauncher.Choice choice =
                new StartupSessionLauncher(storage(), prompter).choose();

        assertInstanceOf(StartupSessionLauncher.Choice.NewSession.class, choice);
        assertFalse(wasCalled.get(), "prompt must NOT render when history is empty");
    }

    @Test
    void cancelChoosesExit() {
        SessionStorage storage = storage();
        storage.save(session("seed", "x"));
        ChoicePrompter<StartupSessionLauncher.Choice> prompter = model -> Optional.empty();

        StartupSessionLauncher.Choice choice =
                new StartupSessionLauncher(storage, prompter).choose();

        assertInstanceOf(StartupSessionLauncher.Choice.Exit.class, choice);
    }

    @Test
    void promptIoFailureFallsBackToNewSession() {
        SessionStorage storage = storage();
        storage.save(session("seed", "x"));
        ChoicePrompter<StartupSessionLauncher.Choice> prompter = model -> {
            throw new IOException("boom");
        };

        StartupSessionLauncher.Choice choice =
                new StartupSessionLauncher(storage, prompter).choose();

        assertInstanceOf(StartupSessionLauncher.Choice.NewSession.class, choice);
    }

    @Test
    void resumeModelMarksCurrentSession() {
        SessionStorage storage = storage();
        storage.save(session("current-session", "current question"));

        var model = SessionSelectModels.resumeModel(
                storage,
                storage.listSessions(),
                "current-session");

        assertTrue(model.options().getFirst().primary().startsWith("Current  "));
        assertTrue(model.options().getFirst().primary().contains("current question"));
    }

    @Test
    void startupResumeLabelsKeepTitleBeforeMetadata() {
        SessionStorage storage = storage();
        storage.save(session("session-123456789", "very important conversation title"));

        AtomicReference<ChoicePrompt.Model<StartupSessionLauncher.Choice>> seen = new AtomicReference<>();
        ChoicePrompter<StartupSessionLauncher.Choice> prompter = model -> {
            seen.set(model);
            return Optional.of(new StartupSessionLauncher.Choice.Exit());
        };

        new StartupSessionLauncher(storage, prompter).choose();

        String primary = seen.get().options().getFirst().primary();
        assertTrue(primary.startsWith("Continue  very important conversation title"));
        assertTrue(primary.contains("session-"));
    }

    // ---- helpers -------------------------------------------------------

    private SessionStorage storage() {
        return new SessionStorage(tempDir.resolve("sessions"));
    }

    private static ConversationSession session(String id, String firstUserMessage) {
        return new ConversationSession(
                id,
                Instant.now(),
                Path.of("."),
                List.of(Message.system("Init"), Message.user(firstUserMessage)));
    }
}
