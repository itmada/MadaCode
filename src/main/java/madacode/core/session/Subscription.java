package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

/**
 * Handle returned by {@link CancellationToken#onCancel(Runnable)} that lets
 * the registrar withdraw the callback before it fires.
 *
 * <p>{@link AutoCloseable#close()} is narrowed to throw nothing checked so
 * callers can use it in try-with-resources without sprinkling
 * {@code catch (Exception)} blocks.
 *
 * <p>Closing is idempotent: closing an already-closed subscription, a
 * subscription whose token has already cancelled, or a no-op subscription
 * (e.g. from {@link CancellationToken#never()}) is always safe.
 */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
