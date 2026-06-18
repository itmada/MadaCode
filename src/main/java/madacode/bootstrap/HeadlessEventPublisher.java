package madacode.bootstrap;

import madacode.events.AppEvent;
import madacode.events.AppEventPublisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A no-op {@link AppEventPublisher} for headless runs (eval, tooling) where there is no
 * TUI to render to. Events are discarded; sequence numbers are still handed out so any
 * consumer relying on monotonic ids keeps working.
 */
final class HeadlessEventPublisher implements AppEventPublisher {

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void publish(AppEvent event) {
        // discard — nothing renders in headless mode
    }

    @Override
    public long nextSequence() {
        return sequence.incrementAndGet();
    }

    @Override
    public void flush(Duration timeout) {
        // nothing buffered
    }

    @Override
    public void close() {
        // nothing to release
    }
}
