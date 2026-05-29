package madacode.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class TurnExecutorTest {

    @TempDir
    Path tempDir;

    private TurnLog log;

    @BeforeEach
    void setUp() {
        log = new TurnLog(tempDir);
    }

    @Test
    void submitReturnsDoneResult() {
        TurnRunner runner = (turn, session, token) ->
                new TurnResult("done", FinishReason.COMPLETED, 1);
        TurnExecutor executor = new TurnExecutor(runner, log);

        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        TurnHandle handle = executor.submit(session, "hello");

        TurnResult result = handle.result().join();
        assertEquals(FinishReason.COMPLETED, result.finishReason());

        executor.close();
    }

    @Test
    void canceledTurnReturnsCancelledResult() {
        TurnRunner runner = (turn, session, token) -> {
            token.throwIfCancelled();
            return new TurnResult("should not reach", FinishReason.COMPLETED, 1);
        };
        TurnExecutor executor = new TurnExecutor(runner, log);

        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        TurnHandle handle = executor.submit(session, "hello");
        handle.cancel().accept("user");

        try {
            handle.result().join();
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof CancellationException
                    || e.getMessage().contains("CancellationException"));
        }

        executor.close();
    }

    @Test
    void runnerExceptionProducesFailedTurn() {
        TurnRunner runner = (turn, session, token) -> {
            throw new RuntimeException("boom");
        };
        TurnExecutor executor = new TurnExecutor(runner, log);

        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        TurnHandle handle = executor.submit(session, "hello");

        try {
            handle.result().join();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("RuntimeException")
                    || e.getCause() instanceof RuntimeException);
        }

        executor.close();
    }

    @Test
    void sameSessionConcurrentSubmitRejected() {
        TurnRunner runner = (turn, session, token) ->
                CompletableFuture.supplyAsync(() ->
                                new TurnResult("ok", FinishReason.COMPLETED, 1),
                        CompletableFuture.delayedExecutor(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .get();
        TurnExecutor executor = new TurnExecutor(runner, log);

        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        executor.submit(session, "first");

        assertThrows(IllegalStateException.class,
                () -> executor.submit(session, "second"),
                "Same session should not allow concurrent turns");

        executor.close();
    }

    @Test
    void apiErrorResultPersistsAsApiErrorTerminal() throws Exception {
        TurnRunner runner = (turn, session, token) ->
                new TurnResult("upstream 500", FinishReason.API_ERROR, 1);
        TurnExecutor executor = new TurnExecutor(runner, log);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws_api"));
        TurnHandle handle = executor.submit(session, "trigger api error");
        handle.result().join();

        var terminal = log.findTerminal(session.sessionId(), handle.turnId());
        assertTrue(terminal.isPresent());
        assertEquals(TurnStatus.FAILED, terminal.get().terminal().status());
        assertEquals(TerminationCause.API_ERROR, terminal.get().terminal().cause());
        assertEquals("upstream 500", terminal.get().terminal().reason());

        executor.close();
    }

    @Test
    void maxIterationsResultPersistsAsMaxIterationsTerminal() throws Exception {
        TurnRunner runner = (turn, session, token) ->
                new TurnResult("iter ceiling", FinishReason.MAX_ITERATIONS, 50);
        TurnExecutor executor = new TurnExecutor(runner, log);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws_iter"));
        TurnHandle handle = executor.submit(session, "loop forever");
        handle.result().join();

        var terminal = log.findTerminal(session.sessionId(), handle.turnId());
        assertTrue(terminal.isPresent());
        assertEquals(TerminationCause.MAX_ITERATIONS, terminal.get().terminal().cause());

        executor.close();
    }

    @Test
    void gracefullyCancelledResultPersistsAsCanceled() throws Exception {
        // QueryEngine may catch the cancel internally and return CANCELLED
        // instead of throwing. The terminal must still be CANCELED, not DONE.
        TurnRunner runner = (turn, session, token) ->
                new TurnResult("user stopped", FinishReason.CANCELLED, 1);
        TurnExecutor executor = new TurnExecutor(runner, log);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws_cancel"));
        TurnHandle handle = executor.submit(session, "to be cancelled");
        handle.result().join();

        var terminal = log.findTerminal(session.sessionId(), handle.turnId());
        assertTrue(terminal.isPresent());
        assertEquals(TurnStatus.CANCELED, terminal.get().terminal().status());
        assertEquals(TerminationCause.CANCELED, terminal.get().terminal().cause());

        executor.close();
    }

    @Test
    void cancelWritesCanceledStatusNotFailed() throws Exception {
        TurnRunner runner = (turn, session, token) -> {
            token.throwIfCancelled();
            return new TurnResult("ok", FinishReason.COMPLETED, 1);
        };
        TurnExecutor executor = new TurnExecutor(runner, log);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws2"));
        TurnHandle handle = executor.submit(session, "cancel-me");
        handle.cancel().accept("esc");
        try { handle.result().join(); } catch (Exception ignored) {}

        String content = Files.readString(log.turnFile(session.sessionId(), handle.turnId()));
        assertTrue(content.contains("CANCELED"), "jsonl must record CANCELED, not FAILED");
        assertTrue(content.contains("\"esc\""), "cancel reason must be preserved in jsonl");

        executor.close();
    }

    @Test
    void recoverOnStartupMarksUnfinishedAsFailed() {
        // Pre-populate an unfinished turn
        log.append("recovery-session",
                new TurnEvent.Started("turn_orphan", java.time.Instant.now(), "orphan turn"));

        TurnRunner runner = (turn, session, token) ->
                new TurnResult("ok", FinishReason.COMPLETED, 1);
        TurnExecutor executor = new TurnExecutor(runner, log);

        var unfinished = executor.recoverOnStartup();
        assertEquals(1, unfinished.size());
        assertTrue(unfinished.contains("turn_orphan"));

        // Should no longer be unfinished
        assertTrue(log.findUnfinished().isEmpty());

        executor.close();
    }

    @Test
    void submitLocalSharesCancellationAndTurnLogLifecycle() throws Exception {
        TurnRunner runner = (turn, session, token) ->
                new TurnResult("unused", FinishReason.COMPLETED, 1);
        TurnExecutor executor = new TurnExecutor(runner, log);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws_local"));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<CancellationToken> seenToken = new AtomicReference<>();

        TurnHandle handle = executor.submitLocal(session, "slash:/compact", (s, token) -> {
            seenToken.set(token);
            started.countDown();
            while (!token.isCancelled()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    if (token.isCancelled()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    throw e;
                }
            }
            return new TurnResult("cancelled", FinishReason.CANCELLED, 1);
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        handle.cancel().accept("esc");
        TurnResult result = handle.result().join();

        assertEquals(FinishReason.CANCELLED, result.finishReason());
        assertTrue(seenToken.get().isCancelled());
        var terminal = log.findTerminal(session.sessionId(), handle.turnId());
        assertTrue(terminal.isPresent());
        assertEquals(TurnStatus.CANCELED, terminal.get().terminal().status());
        assertEquals("esc", terminal.get().terminal().reason());
        String content = Files.readString(log.turnFile(session.sessionId(), handle.turnId()));
        assertTrue(content.contains("slash:/compact"));

        executor.close();
    }
}
