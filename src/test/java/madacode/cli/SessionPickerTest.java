package madacode.cli;

import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.session.SessionStorage;
import madacode.cli.session.SessionPicker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionPickerTest {

    @TempDir
    Path tempDir;

    @Test
    void emptySessionListStartsNewSessionAndExplainsWhy() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SessionPicker picker = picker("", out);

        SessionPicker.PickResult result = picker.pick();

        assertInstanceOf(SessionPicker.PickResult.New.class, result);
        assertTrue(output(out).contains("No recent sessions found"));
    }

    @Test
    void selectingNumberResumesMatchingSession() {
        SessionStorage storage = storage();
        storage.save(session("older", Instant.parse("2026-01-01T00:00:00Z"), "old question"));
        storage.save(session("newer", Instant.parse("2026-01-02T00:00:00Z"), "new question"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SessionPicker picker = new SessionPicker(
                storage,
                new BufferedReader(new StringReader("1\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        SessionPicker.PickResult result = picker.pick();

        SessionPicker.PickResult.Resume resume =
                assertInstanceOf(SessionPicker.PickResult.Resume.class, result);
        assertTrue(List.of("newer", "older").contains(resume.sessionId()));
        assertTrue(output(out).contains("Recent sessions:"));
    }

    @Test
    void invalidChoiceRepromptsThenStartsNewSession() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SessionStorage storage = storage();
        storage.save(session("session-1", Instant.parse("2026-01-01T00:00:00Z"), "question"));
        SessionPicker picker = new SessionPicker(
                storage,
                new BufferedReader(new StringReader("wat\nN\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        SessionPicker.PickResult result = picker.pick();

        assertInstanceOf(SessionPicker.PickResult.New.class, result);
        assertTrue(output(out).contains("Invalid choice"));
    }

    @Test
    void quitReturnsNull() {
        SessionStorage storage = storage();
        storage.save(session("session-1", Instant.parse("2026-01-01T00:00:00Z"), "question"));
        SessionPicker picker = new SessionPicker(
                storage,
                new BufferedReader(new StringReader("Q\n")),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertNull(picker.pick());
    }

    private SessionPicker picker(String input, ByteArrayOutputStream out) {
        return new SessionPicker(
                storage(),
                new BufferedReader(new StringReader(input)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private SessionStorage storage() {
        return new SessionStorage(tempDir.resolve("sessions"));
    }

    private static ConversationSession session(String id, Instant createdAt, String firstUserMessage) {
        return new ConversationSession(
                id,
                createdAt,
                Path.of("."),
                List.of(Message.system("Init"), Message.user(firstUserMessage)));
    }

    private static String output(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }
}
