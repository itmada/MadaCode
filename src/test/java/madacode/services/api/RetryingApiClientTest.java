package madacode.services.api;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.StopReason;
import madacode.core.model.TokenUsage;
import madacode.core.turn.CancellationToken;
import madacode.tool.ToolVisibility;
import madacode.tool.VisibleTools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class RetryingApiClientTest {

    private static RetryingApiClient newClient(ApiClient delegate, int maxRetries) {
        return new RetryingApiClient(
                delegate,
                new RetryOptions(maxRetries, Duration.ofMillis(1), Duration.ofMillis(1)),
                new ApiErrorClassifier(),
                duration -> { },          // no real sleeping in tests
                bound -> 0L);             // deterministic, zero jitter
    }

    /** A retryable mid-stream failure must reset the sink before retrying so the
     *  partial content from the failed attempt is discarded, not spliced onto
     *  the retry's output. */
    @Test
    void retryAfterMidStreamEmissionResetsSinkAndProducesCleanOutput() {
        RecordingSink sink = new RecordingSink();
        int[] attempts = {0};

        ApiClient delegate = (messages, systemPrompt, tools, s, token) -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                s.onTextDelta("partial-from-first");
                // Mid-stream network drop: IO- caused -> classified NETWORK, retryable.
                throw new ApiClientException("connection reset",
                        new IOException("stream closed"));
            }
            s.onTextDelta("full-second");
            return new ApiClient.ApiResponse("full-second", List.of());
        };

        ApiClient.ApiResponse response = newClient(delegate, 3)
                .send(List.of(Message.user("hi")), "sys", ToolVisibility.empty(), sink);

        assertEquals(2, attempts[0], "should have retried exactly once");
        assertEquals("full-second", response.assistantText());
        // The sink saw the partial, then a reset, then the clean retry output.
        assertIterableEquals(
                List.of("text:partial-from-first", "reset", "text:full-second"),
                sink.events);
    }

    /** A non-retryable failure (here: HTTP 401) must propagate without resetting
     *  or retrying. */
    @Test
    void nonRetryableFailurePropagatesWithoutResetOrRetry() {
        RecordingSink sink = new RecordingSink();
        int[] attempts = {0};

        ApiClient delegate = (messages, systemPrompt, tools, s, token) -> {
            attempts[0]++;
            throw ApiClientException.http(401, "unauthorized");
        };

        assertThrows(ApiClientException.class, () -> newClient(delegate, 3)
                .send(List.of(Message.user("hi")), "sys", ToolVisibility.empty(), sink));

        assertEquals(1, attempts[0], "401 is not retryable");
        assertIterableEquals(List.of(), sink.events);
    }

    /** When retries are exhausted the last exception propagates; each retry still
     *  resets the sink so no partial output leaks into a (here absent) success. */
    @Test
    void exhaustedRetriesPropagateAndResetEachAttempt() {
        RecordingSink sink = new RecordingSink();
        int[] attempts = {0};

        ApiClient delegate = (messages, systemPrompt, tools, s, token) -> {
            attempts[0]++;
            s.onTextDelta("chunk" + attempts[0]);
            throw new ApiClientException("connection reset", new IOException("drop"));
        };

        assertThrows(ApiClientException.class, () -> newClient(delegate, 2)
                .send(List.of(Message.user("hi")), "sys", ToolVisibility.empty(), sink));

        assertEquals(3, attempts[0], "initial attempt + 2 retries");
        // Two resets (before retry 1 and retry 2); the final failure does not reset.
        assertIterableEquals(
                List.of("text:chunk1", "reset", "text:chunk2", "reset", "text:chunk3"),
                sink.events);
    }

    private static final class RecordingSink implements ApiStreamSink {
        private final List<String> events = new ArrayList<>();

        @Override public void onTextDelta(String chunk) { events.add("text:" + chunk); }
        @Override public void onToolUseBlock(ContentBlock.ToolUseBlock block) { events.add("tool"); }
        @Override public void onThinkingBlock(ContentBlock.ThinkingBlock block) { events.add("thinking"); }
        @Override public void onMessageStart(String model, TokenUsage initialUsage) { }
        @Override public void onMessageStop(StopReason r, TokenUsage u, long ttftMs, long totalMs) { }
        @Override public void onStreamReset() { events.add("reset"); }
    }
}
