package madacode;

import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiClientException;
import madacode.services.api.ApiErrorClassifier;
import madacode.services.api.ApiStreamSink;
import madacode.core.model.Message;
import madacode.services.api.RetryOptions;
import madacode.services.api.RetryingApiClient;
import madacode.tool.Tool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RetryingApiClientTest {

    private static final ApiStreamSink NOOP_SINK = new ApiStreamSink() {
        public void onTextDelta(String c) {}
        public void onToolUseBlock(ContentBlock.ToolUseBlock b) {}
        public void onThinkingBlock(ContentBlock.ThinkingBlock b) {}
        public void onMessageStart(String m, TokenUsage u) {}
        public void onMessageStop(StopReason r, TokenUsage u, long t, long to) {}
    };

    @Test
    void retriesRateLimitUntilSuccess() {
        FakeApiClient delegate = new FakeApiClient();
        delegate.enqueueFailure(ApiClientException.http(429, "rate limit"));
        delegate.enqueueFailure(ApiClientException.http(429, "rate limit"));
        delegate.enqueueSuccess(new ApiClient.ApiResponse("ok", List.of()));

        ApiClient.ApiResponse response = retrying(delegate, 3)
                .send(List.of(), "system", List.of(), NOOP_SINK);

        assertEquals("ok", response.assistantText());
        assertEquals(3, delegate.callCount());
    }

    @Test
    void retriesServerErrorUntilSuccess() {
        FakeApiClient delegate = new FakeApiClient();
        delegate.enqueueFailure(ApiClientException.http(529, "overloaded"));
        delegate.enqueueSuccess(new ApiClient.ApiResponse("ok", List.of()));

        ApiClient.ApiResponse response = retrying(delegate, 3)
                .send(List.of(), "system", List.of(), NOOP_SINK);

        assertEquals("ok", response.assistantText());
        assertEquals(2, delegate.callCount());
    }

    @Test
    void retriesTimeoutUntilSuccess() {
        FakeApiClient delegate = new FakeApiClient();
        delegate.enqueueFailure(new ApiClientException(
                "timeout",
                new HttpTimeoutException("request timed out")));
        delegate.enqueueSuccess(new ApiClient.ApiResponse("ok", List.of()));

        ApiClient.ApiResponse response = retrying(delegate, 3)
                .send(List.of(), "system", List.of(), NOOP_SINK);

        assertEquals("ok", response.assistantText());
        assertEquals(2, delegate.callCount());
    }

    @Test
    void retriesNetworkErrorUntilSuccess() {
        FakeApiClient delegate = new FakeApiClient();
        delegate.enqueueFailure(new ApiClientException("network failed", new IOException("boom")));
        delegate.enqueueSuccess(new ApiClient.ApiResponse("ok", List.of()));

        ApiClient.ApiResponse response = retrying(delegate, 3)
                .send(List.of(), "system", List.of(), NOOP_SINK);

        assertEquals("ok", response.assistantText());
        assertEquals(2, delegate.callCount());
    }

    @Test
    void doesNotRetryAuthFailure() {
        FakeApiClient delegate = new FakeApiClient();
        ApiClientException failure = ApiClientException.http(401, "bad token");
        delegate.enqueueFailure(failure);

        ApiClientException thrown = assertThrows(
                ApiClientException.class,
                () -> retrying(delegate, 3).send(List.of(), "system", List.of(), NOOP_SINK));

        assertSame(failure, thrown);
        assertEquals(1, delegate.callCount());
    }

    @Test
    void doesNotRetryPromptTooLong() {
        FakeApiClient delegate = new FakeApiClient();
        ApiClientException failure = ApiClientException.http(400, "prompt is too long");
        delegate.enqueueFailure(failure);

        ApiClientException thrown = assertThrows(
                ApiClientException.class,
                () -> retrying(delegate, 3).send(List.of(), "system", List.of(), NOOP_SINK));

        assertSame(failure, thrown);
        assertEquals(1, delegate.callCount());
    }

    @Test
    void stopsAfterMaxRetries() {
        FakeApiClient delegate = new FakeApiClient();
        ApiClientException finalFailure = ApiClientException.http(500, "server error");
        delegate.enqueueFailure(ApiClientException.http(500, "server error"));
        delegate.enqueueFailure(ApiClientException.http(500, "server error"));
        delegate.enqueueFailure(finalFailure);

        ApiClientException thrown = assertThrows(
                ApiClientException.class,
                () -> retrying(delegate, 2).send(List.of(), "system", List.of(), NOOP_SINK));

        assertSame(finalFailure, thrown);
        assertEquals(3, delegate.callCount());
    }

    @Test
    void appliesFullJitterWithinBackoffWindow() {
        FakeApiClient delegate = new FakeApiClient();
        delegate.enqueueFailure(ApiClientException.http(500, "server error"));
        delegate.enqueueSuccess(new ApiClient.ApiResponse("ok", List.of()));
        List<Duration> sleeps = new ArrayList<>();
        List<Long> jitterBounds = new ArrayList<>();
        RetryingApiClient client = new RetryingApiClient(
                delegate,
                new RetryOptions(1, Duration.ofMillis(100), Duration.ofSeconds(1)),
                new ApiErrorClassifier(),
                sleeps::add,
                bound -> {
                    jitterBounds.add(bound);
                    return 42;
                });

        ApiClient.ApiResponse response = client.send(List.of(), "system", List.of(), NOOP_SINK);

        assertEquals("ok", response.assistantText());
        assertEquals(List.of(101L), jitterBounds);
        assertEquals(List.of(Duration.ofMillis(42)), sleeps);
    }

    private RetryingApiClient retrying(FakeApiClient delegate, int maxRetries) {
        return new RetryingApiClient(
                delegate,
                new RetryOptions(maxRetries, Duration.ZERO, Duration.ZERO),
                new ApiErrorClassifier());
    }

    private static final class FakeApiClient implements ApiClient {

        private final Queue<Object> outcomes = new ArrayDeque<>();
        private int callCount;

        void enqueueSuccess(ApiResponse response) { outcomes.add(response); }
        void enqueueFailure(ApiClientException exception) { outcomes.add(exception); }
        int callCount() { return callCount; }

        @Override
        public ApiResponse send(List<Message> messages, String systemPrompt,
                                Collection<Tool<?>> tools, ApiStreamSink sink,
                                CancellationToken cancellationToken) {
            callCount++;
            Object outcome = outcomes.remove();
            if (outcome instanceof ApiClientException exception) throw exception;
            return (ApiResponse) outcome;
        }
    }
}
