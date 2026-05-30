package madacode.services.compact;

import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.turn.CancellationException;
import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FullCompactStrategyTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void findSplitPointKeepsRecentUserRoundWhenLastMessageIsAssistant() {
        FullCompactStrategy strategy = newStrategy(new SummaryClient("summary"));
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.user("old question"),
                Message.assistant("old answer"),
                Message.user("latest question"),
                Message.assistant("latest answer"));

        assertEquals(3, strategy.findSplitPoint(messages, 1));
    }

    @Test
    void findSplitPointIgnoresToolResultUserMessages() {
        FullCompactStrategy strategy = newStrategy(new SummaryClient("summary"));
        ObjectNode input = mapper.createObjectNode().put("path", "README.md");
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.user("first real question"),
                Message.assistant(List.of(new ContentBlock.ToolUseBlock("toolu_1", "Read", input))),
                Message.user(List.of(new ContentBlock.ToolResultBlock("toolu_1", "file contents", true, -1))),
                Message.assistant("tool result handled"),
                Message.user("second real question"));

        assertEquals(1, strategy.findSplitPoint(messages, 2));
    }

    @Test
    void findSplitPointReturnsZeroWhenThereAreNotEnoughRealUserMessages() {
        FullCompactStrategy strategy = newStrategy(new SummaryClient("summary"));
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.assistant("assistant only"));

        assertEquals(0, strategy.findSplitPoint(messages, 1));
        assertEquals(0, strategy.findSplitPoint(List.of(), 1));
    }

    @Test
    void applySummarizesOldMessagesAndPreservesRecentRound() {
        SummaryClient compactClient = new SummaryClient("dense compact summary");
        List<FullCompactStrategy.CompactEvent> events = new ArrayList<>();
        FullCompactStrategy strategy = new FullCompactStrategy(compactClient, estimator, events::add);
        ConversationSession session = session(
                Message.user("old question 1"),
                Message.assistant("old answer 1 " + "x".repeat(500)),
                Message.user("old question 2"),
                Message.assistant("old answer 2 " + "y".repeat(500)),
                Message.user("latest question"),
                Message.assistant("latest answer"));

        Optional<CompactResult> result = strategy.apply(session, new CompactBudget(1_000, 0.5, 1, 100), madacode.core.turn.CancellationToken.never());

        assertTrue(result.isPresent());
        assertEquals(1, compactClient.calls);
        assertTrue(compactClient.lastRequestText.contains("old question 1"));
        assertTrue(compactClient.lastRequestText.contains("old question 2"));
        assertFalse(compactClient.lastRequestText.contains("latest question"));
        assertTrue(events.stream().anyMatch(e -> e instanceof FullCompactStrategy.CompactEvent.Summarizing));

        List<Message> rebuilt = session.messages();
        assertEquals(MessageRole.SYSTEM, rebuilt.get(0).role());
        assertEquals(MessageRole.USER, rebuilt.get(1).role());
        assertTrue(rebuilt.get(1).content().contains("CompactBoundary"));
        assertTrue(rebuilt.get(1).content().contains("dense compact summary"));
        assertEquals("latest question", rebuilt.get(2).content());
        assertEquals("latest answer", rebuilt.get(3).content());
    }

    @Test
    void applyDoesNothingWhenSplitWouldCompactTooLittle() {
        SummaryClient compactClient = new SummaryClient("summary");
        FullCompactStrategy strategy = newStrategy(compactClient);
        ConversationSession session = session(
                Message.user("old question"),
                Message.assistant("old answer"),
                Message.user("latest question"));

        Optional<CompactResult> result = strategy.apply(session, new CompactBudget(1_000, 0.5, 1, 100), madacode.core.turn.CancellationToken.never());

        assertTrue(result.isEmpty());
        assertEquals(0, compactClient.calls);
        assertEquals(4, session.messages().size());
    }

    @Test
    void applyDoesNothingWhenCompactApiReturnsBlankSummary() {
        SummaryClient compactClient = new SummaryClient(" ");
        FullCompactStrategy strategy = newStrategy(compactClient);
        ConversationSession session = session(
                Message.user("old question 1"),
                Message.assistant("old answer 1"),
                Message.user("old question 2"),
                Message.assistant("old answer 2"),
                Message.user("latest question"));

        Optional<CompactResult> result = strategy.apply(session, new CompactBudget(1_000, 0.5, 1, 100), madacode.core.turn.CancellationToken.never());

        assertTrue(result.isEmpty());
        assertEquals(1, compactClient.calls);
        assertEquals(6, session.messages().size());
    }

    @Test
    void compactApiCallReceivesPassedCancellationToken() {
        // Bug 9 regression: the strategy used to pass CancellationToken.never()
        // hardcoded — meaning Ctrl+C couldn't interrupt a slow compact API call.
        // Now the caller's token is threaded through to apiClient.send.
        java.util.concurrent.atomic.AtomicReference<CancellationToken> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        ApiClient capturing = new ApiClient() {
            @Override
            public ApiResponse send(List<Message> messages, String systemPrompt,
                                    Collection<Tool<?>> tools, ApiStreamSink sink,
                                    CancellationToken cancellationToken) {
                seen.set(cancellationToken);
                return new ApiResponse("summary", List.of());
            }
        };
        FullCompactStrategy strategy = new FullCompactStrategy(capturing, estimator, e -> {});
        ConversationSession session = session(
                Message.user("old q 1"), Message.assistant("old a 1 " + "x".repeat(500)),
                Message.user("old q 2"), Message.assistant("old a 2 " + "y".repeat(500)),
                Message.user("latest q"), Message.assistant("latest a"));

        CancellationToken explicit = CancellationToken.create();
        strategy.apply(session, new CompactBudget(1_000, 0.5, 1, 100), explicit);

        org.junit.jupiter.api.Assertions.assertSame(explicit, seen.get(),
                "compact must forward the caller's token, not silently swap in never()");
    }

    @Test
    void applyPropagatesCancellationInsteadOfSwallowingAsEmpty() {
        CancellationToken token = CancellationToken.create();
        ApiClient cancelling = new ApiClient() {
            @Override
            public ApiResponse send(List<Message> messages, String systemPrompt,
                                    Collection<Tool<?>> tools, ApiStreamSink sink,
                                    CancellationToken cancellationToken) {
                cancellationToken.cancel("esc");
                throw new RuntimeException("request aborted");
            }
        };
        FullCompactStrategy strategy = new FullCompactStrategy(cancelling, estimator, e -> {});
        ConversationSession session = session(
                Message.user("old q 1"), Message.assistant("old a 1 " + "x".repeat(500)),
                Message.user("old q 2"), Message.assistant("old a 2 " + "y".repeat(500)),
                Message.user("latest q"), Message.assistant("latest a"));

        CancellationException thrown = assertThrows(CancellationException.class,
                () -> strategy.apply(session, new CompactBudget(1_000, 0.5, 1, 100), token));

        assertTrue(thrown.getMessage().contains("esc"));
    }

    private FullCompactStrategy newStrategy(ApiClient apiClient) {
        return new FullCompactStrategy(apiClient, estimator, e -> {});
    }

    private static ConversationSession session(Message... messages) {
        List<Message> all = new ArrayList<>();
        all.add(Message.system("Init"));
        all.addAll(List.of(messages));
        return new ConversationSession("full-compact-test", Instant.now(), Path.of("."), all);
    }

    private static final class SummaryClient implements ApiClient {

        private final String summary;
        private int calls;
        private String lastRequestText = "";

        private SummaryClient(String summary) {
            this.summary = summary;
        }

        @Override
        public ApiResponse send(
                List<Message> messages,
                String systemPrompt,
                Collection<Tool<?>> tools,
                ApiStreamSink sink,
                CancellationToken cancellationToken) {
            calls++;
            lastRequestText = messages.getFirst().content();
            return new ApiResponse(summary, List.of());
        }
    }
}
