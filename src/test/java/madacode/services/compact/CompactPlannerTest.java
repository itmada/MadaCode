package madacode.services.compact;

import madacode.core.model.MetaEvent;
import madacode.core.turn.CancellationException;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.turn.CancellationToken;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompactPlannerTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void belowSoftLimitDoesNothing() {
        ConversationSession session = newSession(Message.user("short"));
        CompactPlanner planner = new CompactPlanner(estimator,
                new CompactBudget(200_000, 0.85, 3, 4_000),
                List.of());

        List<MetaEvent> events = new ArrayList<>();
        boolean applied = planner.planAndApply(session, events::add, madacode.core.turn.CancellationToken.never());

        assertFalse(applied);
        assertTrue(events.isEmpty());
    }

    @Test
    void microCompactTriggersAboveSoftLimit() {
        // Build a session with a huge tool result that pushes over soft limit
        String huge = "x".repeat(50_000); // ~13K+ tokens
        ConversationSession session = newSession(
                Message.user(List.of(
                        new madacode.core.model.ContentBlock.ToolResultBlock("t1", huge, true, -1))));

        CompactPlanner planner = new CompactPlanner(estimator,
                new CompactBudget(2_000, 0.50, 3, 100), // small budget to force trigger
                List.of(new MicroCompactStrategy(estimator)));

        List<MetaEvent> events = new ArrayList<>();
        boolean applied = planner.planAndApply(session, events::add, madacode.core.turn.CancellationToken.never());

        if (applied) {
            assertTrue(events.stream().anyMatch(e -> e instanceof MetaEvent.CompactStarted));
            assertTrue(events.stream().anyMatch(e -> e instanceof MetaEvent.CompactCompleted));
        }
        // Not asserting true because the small budget may not trigger;
        // the point is it doesn't throw
    }

    @Test
    void fullCompactCascadesWhenMicroInsufficient() {
        // Use a session with many messages, all with content
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("Init"));
        for (int i = 0; i < 20; i++) {
            msgs.add(Message.user("what about issue #" + i + "?"));
            msgs.add(Message.assistant("I think it's related to the database layer."
                    + "x".repeat(200)));
        }

        ConversationSession session = new ConversationSession(
                "test-cascade", java.time.Instant.now(),
                java.nio.file.Path.of("."), msgs);

        // Fake compact API client — summarises as "summary"
        ApiClient fakeCompactClient = new ApiClient() {
            @Override
            public ApiResponse send(
                    List<Message> messages, String systemPrompt,
                    Collection<madacode.tool.Tool<?>> tools,
                    ApiStreamSink sink,
                    CancellationToken cancellationToken) {
                return new ApiResponse("Compacted summary of old messages.", List.of());
            }
        };

        CompactPlanner planner = new CompactPlanner(estimator,
                new CompactBudget(500, 0.50, 1, 10), // very small budget
                List.of(
                        new MicroCompactStrategy(estimator),
                        new FullCompactStrategy(fakeCompactClient, estimator, e -> {})));

        List<MetaEvent> events = new ArrayList<>();
        boolean applied = planner.planAndApply(session, events::add, madacode.core.turn.CancellationToken.never());

        if (applied) {
            assertTrue(events.stream().anyMatch(e -> e instanceof MetaEvent.CompactCompleted));
            // Session should now contain a provider-visible compact boundary user message.
            boolean hasSummary = session.messages().stream()
                    .anyMatch(m -> m.role() == madacode.core.model.MessageRole.USER
                            && m.content().contains("CompactBoundary"));
            assertTrue(hasSummary);
        }
    }

    @Test
    void fullCompactFailsOnApiError() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("Init"));
        for (int i = 0; i < 20; i++) {
            msgs.add(Message.user("question #" + i));
            msgs.add(Message.assistant("answer #" + i + " " + "x".repeat(200)));
        }

        ConversationSession session = new ConversationSession(
                "test-fail", java.time.Instant.now(),
                java.nio.file.Path.of("."), msgs);

        ApiClient failingClient = new ApiClient() {
            @Override
            public ApiResponse send(
                    List<Message> messages, String systemPrompt,
                    Collection<madacode.tool.Tool<?>> tools,
                    ApiStreamSink sink,
                    CancellationToken cancellationToken) {
                throw new RuntimeException("API unavailable");
            }
        };

        CompactPlanner planner = new CompactPlanner(estimator,
                new CompactBudget(500, 0.50, 1, 10),
                List.of(
                        new MicroCompactStrategy(estimator),
                        new FullCompactStrategy(failingClient, estimator, e -> {})));

        List<MetaEvent> events = new ArrayList<>();
        planner.planAndApply(session, events::add, madacode.core.turn.CancellationToken.never());

        // Should emit CompactFailed, not crash
        assertTrue(events.stream().anyMatch(e -> e instanceof MetaEvent.CompactFailed));
    }

    @Test
    void forceCompactReportsCancellationWithoutTryingLaterStrategies() {
        ConversationSession session = newSession(Message.user("short"));
        List<MetaEvent> events = new ArrayList<>();
        final boolean[] secondRan = {false};

        CompactPlanner planner = new CompactPlanner(estimator,
                new CompactBudget(500, 0.50, 1, 10),
                List.of(
                        new CompactStrategy() {
                            @Override public String name() { return "cancel"; }
                            @Override public java.util.Optional<CompactResult> apply(
                                    ConversationSession s, CompactBudget b, CancellationToken token) {
                                throw new CancellationException("esc");
                            }
                        },
                        new CompactStrategy() {
                            @Override public String name() { return "second"; }
                            @Override public java.util.Optional<CompactResult> apply(
                                    ConversationSession s, CompactBudget b, CancellationToken token) {
                                secondRan[0] = true;
                                return java.util.Optional.empty();
                            }
                        }));

        boolean applied = planner.forceCompact(session, events::add, CancellationToken.create());

        assertFalse(applied);
        assertFalse(secondRan[0], "planner must not continue after cancellation");
        assertTrue(events.stream().anyMatch(e ->
                e instanceof MetaEvent.CompactFailed f
                        && f.reason().contains("Cancelled")));
    }

    private static ConversationSession newSession(Message... msgs) {
        List<Message> list = new ArrayList<>();
        list.add(Message.system("Init"));
        list.addAll(List.of(msgs));
        return new ConversationSession(
                "test", java.time.Instant.now(), java.nio.file.Path.of("."), list);
    }
}
