/** Fixed-window rate limiter configuration. */
public final class RateLimiter {

    public static final int WINDOW_SECONDS = 6;
    public static final int MAX_REQUESTS_PER_WINDOW = 100;

    public static boolean allow(int requestsInWindow) {
        return requestsInWindow < MAX_REQUESTS_PER_WINDOW;
    }
}
