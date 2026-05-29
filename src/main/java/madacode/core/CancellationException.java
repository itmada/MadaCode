package madacode.core;

/**
 * Thrown when an operation observes that its {@link CancellationToken} was
 * cancelled. Unchecked because cancellation is rarely a recoverable
 * condition for the in-flight call — the catcher typically just unwinds.
 */
public final class CancellationException extends RuntimeException {

    public CancellationException(String reason) {
        super(reason == null ? "cancelled" : reason);
    }
}
