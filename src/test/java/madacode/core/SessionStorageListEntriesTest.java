package madacode.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 7 regression: corrupted transcript files must appear in listEntries()
 * with a reason, not silently vanish from the listing.
 */
class SessionStorageListEntriesTest {

    @TempDir
    Path tempDir;

    @Test
    void corruptedFileAppearsInListEntries() throws Exception {
        // Write a valid session.
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        session.addMessage(Message.user("hello"));
        storage.save(session);

        // Write a corrupted file alongside it.
        Path corrupted = tempDir.resolve("bad-session.json");
        Files.writeString(corrupted, "not valid json {{{");

        List<SessionListEntry> entries = storage.listEntries();

        assertEquals(2, entries.size(), "both files should appear");
        long summaryCount = entries.stream()
                .filter(e -> e instanceof SessionStorage.SessionSummary).count();
        long corruptedCount = entries.stream()
                .filter(e -> e instanceof SessionListEntry.Corrupted).count();
        assertEquals(1, summaryCount);
        assertEquals(1, corruptedCount);

        SessionListEntry.Corrupted c = entries.stream()
                .filter(e -> e instanceof SessionListEntry.Corrupted)
                .map(e -> (SessionListEntry.Corrupted) e)
                .findFirst().orElseThrow();
        assertTrue(c.reason() != null && !c.reason().isBlank(),
                "reason should describe the parse failure");
        assertTrue(c.path().getFileName().toString().equals("bad-session.json"));
    }

    @Test
    void corruptedFileDoesNotAppearInListSessions() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        storage.save(session);

        Path corrupted = tempDir.resolve("broken.json");
        Files.writeString(corrupted, "{\"sessionId\": 12345}"); // wrong type

        List<SessionStorage.SessionSummary> sessions = storage.listSessions();
        assertEquals(1, sessions.size(), "corrupted must not leak into listSessions");
        assertEquals(session.sessionId(), sessions.getFirst().sessionId());
    }

    @Test
    void unsupportedSchemaVersionReportsAsCorrupted() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        Path future = tempDir.resolve("future.json");
        Files.writeString(future, """
                {"schemaVersion": 999, "sessionId": "x", "createdAt": "2025-01-01T00:00:00Z",
                 "workingDirectory": "/tmp", "messages": []}
                """);

        List<SessionListEntry> entries = storage.listEntries();
        assertEquals(1, entries.size());
        SessionListEntry.Corrupted c = assertInstanceOf(
                SessionListEntry.Corrupted.class, entries.getFirst());
        assertTrue(c.reason().contains("999"),
                () -> "reason should mention the version: " + c.reason());
    }
}
