package madacode.cli.mode;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnHandle;
import madacode.core.turn.TurnLog;
import madacode.core.turn.TurnResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonModeHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesLegacyAddInputExpandAndSubmitFlow() throws Exception {
        Path workingDirectory = tempDir.resolve("ws");
        Files.createDirectories(workingDirectory);
        Files.writeString(workingDirectory.resolve("note.txt"), "hello");

        AtomicReference<String> seenInput = new AtomicReference<>();
        TurnExecutor executor = new TurnExecutor(
                (turn, session, token) -> {
                    seenInput.set(turn.userInput());
                    return new TurnResult("ok", FinishReason.COMPLETED, 1);
                },
                new TurnLog(tempDir.resolve("turns")));
        ConversationSession session = new ConversationSession(workingDirectory);

        try {
            CommonModeHandler handler = new CommonModeHandler(executor);
            TurnHandle handle = handler.handle("read @note.txt", session).handle();
            handle.result().join();
        } finally {
            executor.close();
        }

        assertEquals("read @note.txt", session.inputHistory().getFirst());
        assertTrue(seenInput.get().contains("<file path=\"note.txt\">"));
        assertTrue(seenInput.get().contains("hello"));
    }
}
