package madacode.events;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class AppEvents {

    private static final AtomicLong sequence = new AtomicLong();
    private static volatile AppEventPublisher compatibilityPublisher = new BootstrapFallbackPublisher();

    private AppEvents() {
    }

    /**
     * Compatibility publisher for early bootstrap and legacy static callers.
     *
     * <p>Application bootstrap should prefer explicit {@link AppEventPublisher}
     * wiring instead of reaching through this global accessor.
     */
    public static AppEventPublisher publisher() {
        return compatibilityPublisher;
    }

    /**
     * Installs the compatibility publisher used by legacy static callers.
     *
     * <p>Bootstrap code should pass publishers explicitly where practical.
     */
    public static void install(AppEventPublisher publisher) {
        compatibilityPublisher = Objects.requireNonNull(publisher, "publisher");
    }

    public static long nextSequence() {
        return sequence.incrementAndGet();
    }

    public static void resetForTests() {
        compatibilityPublisher = new BootstrapFallbackPublisher();
    }
}
