package madacode.services.api;

import java.time.Duration;

public record RetryOptions(
        int maxRetries,
        Duration initialDelay,
        Duration maxDelay) {

    public static RetryOptions defaults() {
        return new RetryOptions(
                3,
                Duration.ofMillis(500),
                Duration.ofSeconds(5));
    }
}
