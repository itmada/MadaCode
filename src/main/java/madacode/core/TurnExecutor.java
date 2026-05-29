package madacode.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * Submits turns for execution, tracks active cancellations, recovers unfinished
 * turns on startup, and enforces per-session serial execution.
 */
public final class TurnExecutor implements AutoCloseable {

    private final TurnRunner runner;
    private final TurnLog log;
    private final ExecutorService pool;

    // sessionId → turnId of the currently running turn (enforces serial execution)
    private final Map<String, String> runningTurns = new ConcurrentHashMap<>();
    // turnId → CancellationToken for cancel support
    private final Map<String, CancellationToken> activeTokens = new ConcurrentHashMap<>();
    // turnId → worker Thread for thread.interrupt support
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();

    public TurnExecutor(TurnRunner runner, TurnLog log) {
        this(runner, log, Executors.newSingleThreadExecutor(turnThreadFactory()));
    }

    public TurnExecutor(TurnRunner runner, TurnLog log, ExecutorService pool) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.log = Objects.requireNonNull(log, "log");
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    /**
     * Submits a turn for execution. Blocks the caller until the turn
     * completes, fails, or is cancelled.
     *
     * @throws IllegalStateException if this session already has a running turn
     */
    public TurnHandle submit(ConversationSession session, String userInput) {
        return submitManaged(session, userInput,
                (turn, s, token) -> runner.run(turn, s, token));
    }

    /**
     * Submits a local turn for execution. Local turns do not invoke the model
     * loop, but they share the same cancellation, logging, per-session
     * serialization, and worker-thread interrupt semantics as regular turns.
     */
    public TurnHandle submitLocal(ConversationSession session, String label, LocalTurnTask task) {
        Objects.requireNonNull(task, "task");
        return submitManaged(session, label,
                (turn, s, token) -> task.run(s, token));
    }

    private TurnHandle submitManaged(ConversationSession session, String inputOrLabel,
                                     ManagedTurnBody body) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(inputOrLabel, "inputOrLabel");
        Objects.requireNonNull(body, "body");

        Turn turn = Turn.create(session.sessionId(), inputOrLabel);
        if (runningTurns.putIfAbsent(session.sessionId(), turn.id()) != null) {
            throw new IllegalStateException(
                    "Session " + session.sessionId() + " already has a running turn");
        }

        log.append(session.sessionId(),
                new TurnEvent.Started(turn.id(), Instant.now(), inputOrLabel));

        CancellationToken token = CancellationToken.create();
        activeTokens.put(turn.id(), token);

        CompletableFuture<TurnResult> future = CompletableFuture.supplyAsync(() -> {
            activeThreads.put(turn.id(), Thread.currentThread());
            Turn running = turn.withStatus(TurnStatus.RUNNING).withStarted(Instant.now());
            try {
                TurnResult result = body.run(running, session, token);
                log.append(session.sessionId(),
                        new TurnEvent.Finished(turn.id(), Instant.now(),
                                TerminalState.fromResult(result, token)));
                return result;
            } catch (Exception e) {
                if (e instanceof InterruptedException || e instanceof CancellationException) {
                    String reason = token.isCancelled() ? token.reason() : "interrupted";
                    log.append(session.sessionId(),
                            new TurnEvent.Finished(turn.id(), Instant.now(),
                                    TerminalState.canceled(reason)));
                } else if (token.isCancelled()) {
                    log.append(session.sessionId(),
                            new TurnEvent.Finished(turn.id(), Instant.now(),
                                    TerminalState.canceled(token.reason())));
                } else {
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.append(session.sessionId(),
                            new TurnEvent.Finished(turn.id(), Instant.now(),
                                    TerminalState.failed(reason)));
                }
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            } finally {
                activeTokens.remove(turn.id());
                activeThreads.remove(turn.id());
                runningTurns.remove(session.sessionId());
            }
        }, pool);

        Consumer<String> cancelFn = reason -> cancel(turn.id(), reason);
        return new TurnHandle(turn.id(), future, cancelFn);
    }

    @FunctionalInterface
    private interface ManagedTurnBody {
        TurnResult run(Turn turn, ConversationSession session, CancellationToken token)
                throws Exception;
    }

    public boolean cancel(String turnId) {
        return cancel(turnId, "user");
    }

    public boolean cancel(String turnId, String reason) {
        CancellationToken token = activeTokens.get(turnId);
        if (token == null || token.isCancelled()) {
            return false;
        }
        token.cancel(reason);
        Thread worker = activeThreads.get(turnId);
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    /**
     * Finds unfinished turns on disk, marks them FAILED, and returns their IDs.
     * Call once at startup before accepting new turns.
     */
    public List<String> recoverOnStartup() {
        List<String> unfinished = log.findUnfinished();
        for (String turnId : unfinished) {
            log.markRestartFailed(turnId);
        }
        return unfinished;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private static ThreadFactory turnThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "mada-turn-exec");
            t.setDaemon(true);
            return t;
        };
    }
}
