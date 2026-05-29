package madacode.events;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class AppEvents {

    private static final AtomicLong sequence = new AtomicLong();
    private static volatile AppEventPublisher instance = new BootstrapFallbackPublisher();

    private AppEvents() {
    }

    public static AppEventPublisher publisher() {
        return instance;
    }

    public static void install(AppEventPublisher publisher) {
        instance = Objects.requireNonNull(publisher, "publisher");
    }

    public static long nextSequence() {
        return sequence.incrementAndGet();
    }

    public static void resetForTests() {
        instance = new BootstrapFallbackPublisher();
    }
}
