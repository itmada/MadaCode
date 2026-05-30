package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Cooperative cancellation signal that propagates a single user-visible
 * intent ("stop this turn now") across the agent stack:
 * QueryEngine → ToolOrchestrator → ToolExecutor → Tool, and sideways into
 * blocking resources (HTTP requests, child processes).
 *
 * <p>Intentionally separate from {@link Thread#interrupt()} for two reasons:
 * <ul>
 *   <li>One user gesture (Ctrl+C) typically needs to cancel many concurrent
 *       Futures, an in-flight HTTP request, and one or more child processes
 *       at once. A shared token is cleaner than fanning out interrupts.</li>
 *   <li>Cancellation must survive thread boundaries: the model loop, the
 *       virtual-thread tool batch, and the HTTP I/O thread are different
 *       carrier threads, and an interrupt on one wouldn't reach the others.</li>
 * </ul>
 *
 * <p>Callers in long loops (e.g. Grep walking a directory tree) should poll
 * {@link #isCancelled()} or call {@link #throwIfCancelled()} between
 * iterations. Callers that hold blocking resources should
 * {@link #onCancel(Runnable) register} a teardown action — {@code Process}
 * destruction, {@code HttpClient} request abort, etc. — that fires the moment
 * cancellation is requested.
 *
 * <p>Thread-safety: callers can safely call any method from any thread.
 * Callbacks registered via {@link #onCancel} are invoked exactly once in
 * registration order. Exceptions thrown by callbacks are swallowed so a
 * misbehaving callback can't block subsequent cleanup.
 *
 * <p>Lifecycle: a token is created per-turn ({@code CancellationToken.create()})
 * and becomes unreachable when the turn ends, so registered callbacks are
 * collected with the token. No explicit unsubscribe is needed.
 */
public class CancellationToken {

    /**
     * Singleton no-op token. Both {@link #cancel} and {@link #onCancel} are
     * overridden: cancel is a no-op so a stray cancel can't poison shared
     * callers, and onCancel drops the callback entirely — because this
     * instance is a process-level singleton, any registered callback would
     * otherwise live for the JVM's lifetime.
     */
    private static final Subscription NOOP_SUBSCRIPTION = () -> {};

    public static final String REASON_PERMISSION_DENIED = "permission_denied";

    private static final CancellationToken NEVER = new CancellationToken() {
        @Override
        public void cancel(String reason) {
            // intentionally no-op
        }
        @Override
        public Subscription onCancel(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            // intentionally drop — this singleton can never cancel, and
            // accumulating callbacks here would be a slow leak.
            return NOOP_SUBSCRIPTION;
        }
    };

    private final Object lock = new Object();
    private volatile boolean cancelled;
    private volatile String reason;
    // LinkedHashMap preserves insertion order for fire sequence AND gives O(1)
    // removal by id — needed so a Subscription can withdraw its callback.
    private final LinkedHashMap<Long, Runnable> callbacks = new LinkedHashMap<>();
    private long nextCallbackId = 0;

    /**
     * A token that is never cancelled. Useful for tests and headless paths
     * where there is no user to press Ctrl+C.
     */
    public static CancellationToken never() {
        return NEVER;
    }

    /** Creates a fresh cancellable token. */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** The reason supplied at cancellation, or {@code null} if not cancelled. */
    public String reason() {
        return reason;
    }

    /**
     * Marks this token cancelled and synchronously invokes every registered
     * callback. Subsequent calls are no-ops (the first reason wins).
     */
    public void cancel(String reason) {
        List<Runnable> toFire;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            this.reason = reason == null ? "cancelled" : reason;
            toFire = new ArrayList<>(callbacks.values());
            callbacks.clear();
        }
        for (Runnable cb : toFire) {
            try {
                cb.run();
            } catch (RuntimeException ignored) {
                // A buggy teardown callback must not block other cleanup.
            }
        }
    }

    /**
     * Registers a callback to fire when (or immediately, if already)
     * cancelled. Returns a {@link Subscription} the registrar can close to
     * withdraw the callback before it fires — e.g. when a tool's blocking
     * resource has been cleaned up normally and the kill-hook is no longer
     * needed.
     *
     * <p>If the token is already cancelled, the callback runs immediately and
     * the returned subscription is a no-op (the callback already fired; there
     * is nothing to withdraw).
     */
    public Subscription onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        boolean fireNow;
        long id;
        synchronized (lock) {
            if (cancelled) {
                fireNow = true;
                id = -1L;
            } else {
                id = nextCallbackId++;
                callbacks.put(id, callback);
                fireNow = false;
            }
        }
        if (fireNow) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // see cancel()
            }
            return NOOP_SUBSCRIPTION;
        }
        return () -> {
            synchronized (lock) {
                callbacks.remove(id);   // safe and idempotent if already gone
            }
        };
    }

    /**
     * Throws {@link CancellationException} if cancellation has been
     * requested. Use in long loops to bail out cooperatively.
     */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancellationException(reason);
        }
    }
}
