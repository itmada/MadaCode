package madacode.core.session;

import madacode.core.model.*;
import madacode.core.turn.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionStorageExtraTest {

    @TempDir
    Path tempDir;

    @Test
    void deleteRemovesTranscript() {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = newSession("del-1", storage);
        storage.save(session);
        assertTrue(Files.exists(storage.transcriptPath(session.sessionId())));

        storage.delete(session.sessionId());

        assertFalse(Files.exists(storage.transcriptPath(session.sessionId())));
    }

    @Test
    void deleteNonExistentDoesNotThrow() {
        SessionStorage storage = new SessionStorage(tempDir);
        storage.delete("nonexistent-id");
    }

    @Test
    void loadIfExistsReturnsPresent() {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = newSession("load-1", storage);
        storage.save(session);

        Optional<ConversationSession> result = storage.loadIfExists(session.sessionId());

        assertTrue(result.isPresent());
        assertEquals(session.sessionId(), result.get().sessionId());
    }

    @Test
    void loadIfExistsReturnsEmpty() {
        SessionStorage storage = new SessionStorage(tempDir);
        Optional<ConversationSession> result = storage.loadIfExists("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsTraversalSessionIds() {
        SessionStorage storage = new SessionStorage(tempDir);

        assertThrows(IllegalArgumentException.class, () -> storage.transcriptPath("../escape"));
        assertThrows(IllegalArgumentException.class, () -> storage.loadIfExists("../escape"));
        assertThrows(IllegalArgumentException.class, () -> storage.delete("../escape"));
    }

    @Test
    void findMostRecentReturnsLatest() {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession older = newSession("older", storage);
        ConversationSession newer = newSession("newer", storage);
        storage.save(older);
        // Brief pause to ensure different mtime
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        storage.save(newer);

        Optional<SessionStorage.SessionSummary> recent = storage.findMostRecent();

        assertTrue(recent.isPresent());
        assertEquals("newer", recent.get().sessionId());
    }

    private static ConversationSession newSession(String id, SessionStorage storage) {
        return new ConversationSession(
                id,
                Instant.now(),
                Path.of("."),
                List.of(Message.system("Init"), Message.user("hello")));
    }
}
