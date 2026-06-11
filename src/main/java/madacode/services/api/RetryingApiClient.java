package madacode.services.api;

import madacode.core.turn.CancellationToken;
import madacode.core.model.Message;
import madacode.logging.DefaultDiagnosticEvents;
import madacode.logging.DiagnosticEvents;
import madacode.tool.Tool;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;
import java.util.random.RandomGenerator;

public final class RetryingApiClient implements ApiClient {

    private final ApiClient delegate;
    private final RetryOptions options;
    private final ApiErrorClassifier classifier;
    private final DiagnosticEvents diagnosticEvents;
    private final Consumer<Duration> sleeper;
    private final LongUnaryOperator jitterMillis;

    public RetryingApiClient(
            ApiClient delegate,
            RetryOptions options,
            ApiErrorClassifier classifier) {
        this(delegate, options, classifier, new DefaultDiagnosticEvents());
    }

    public RetryingApiClient(
            ApiClient delegate,
            RetryOptions options,
            ApiErrorClassifier classifier,
            DiagnosticEvents diagnosticEvents) {
        this(delegate, options, classifier, diagnosticEvents,
                RetryingApiClient::threadSleep,
                bound -> RandomGenerator.getDefault().nextLong(bound));
    }

    public RetryingApiClient(
            ApiClient delegate,
            RetryOptions options,
            ApiErrorClassifier classifier,
            Consumer<Duration> sleeper,
            LongUnaryOperator jitterMillis) {
        this(delegate, options, classifier, new DefaultDiagnosticEvents(), sleeper, jitterMillis);
    }

    public RetryingApiClient(
            ApiClient delegate,
            RetryOptions options,
            ApiErrorClassifier classifier,
            DiagnosticEvents diagnosticEvents,
            Consumer<Duration> sleeper,
            LongUnaryOperator jitterMillis) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.options = Objects.requireNonNull(options, "options");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.diagnosticEvents = Objects.requireNonNull(diagnosticEvents, "diagnosticEvents");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.jitterMillis = Objects.requireNonNull(jitterMillis, "jitterMillis");
    }

    @Override
    public ApiResponse send(
            List<Message> messages,
            String systemPrompt,
            Collection<Tool<?>> tools,
            ApiStreamSink sink,
            CancellationToken cancellationToken) {
        int attempt = 0;

        while (true) {
            try {
                return delegate.send(messages, systemPrompt, tools, sink, cancellationToken);
            } catch (ApiClientException exception) {
                if (cancellationToken.isCancelled()) {
                    throw new madacode.core.turn.CancellationException(cancellationToken.reason());
                }
                ApiError error = classifier.classify(exception);

                if (!error.retryable() || attempt >= options.maxRetries()) {
                    diagnosticEvents.apiFinalFailure(
                            attempt + 1, error.type().name(), error.retryable());
                    throw exception;
                }

                Duration backoff = backoffDelay(attempt);
                diagnosticEvents.apiRetry(
                        attempt + 1, options.maxRetries(), error.type().name(), backoff.toMillis());
                sleeper.accept(backoff);
                if (cancellationToken.isCancelled()) {
                    throw new madacode.core.turn.CancellationException(cancellationToken.reason());
                }
                attempt++;
            }
        }
    }

    private Duration backoffDelay(int attempt) {
        long base = options.initialDelay().toMillis();
        long exponentialDelay = safeMultiply(base, 1L << Math.min(attempt, 62));
        long cappedDelay = Math.min(exponentialDelay, options.maxDelay().toMillis());
        if (cappedDelay <= 0) return Duration.ZERO;
        long jitteredDelay = jitterMillis.applyAsLong(cappedDelay + 1);
        return Duration.ofMillis(clamp(jitteredDelay, 0, cappedDelay));
    }

    private static long safeMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void threadSleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiClientException("Interrupted while waiting to retry API request", interrupted);
        }
    }
}
