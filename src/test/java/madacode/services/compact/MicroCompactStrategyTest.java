package madacode.services.compact;

import madacode.core.model.ContentBlock;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MicroCompactStrategyTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final MicroCompactStrategy strategy = new MicroCompactStrategy(estimator);
    private final CompactBudget budget = CompactBudget.defaults();

    @Test
    void noLongToolResultsReturnsEmpty() {
        ConversationSession session = newSession(
                Message.user("hello"),
                Message.assistant("hi"));

        Optional<CompactResult> result = strategy.apply(session, budget, madacode.core.turn.CancellationToken.never());

        assertTrue(result.isEmpty());
    }

    @Test
    void truncatesOversizedToolResult() {
        String hugeContent = "x".repeat(10_000);
        ConversationSession session = newSession(
                Message.user(List.of(new ContentBlock.ToolResultBlock("t1", hugeContent, true, -1))));

        Optional<CompactResult> result = strategy.apply(session, budget, madacode.core.turn.CancellationToken.never());

        assertTrue(result.isPresent());
        assertTrue(result.get().changed());
        assertEquals("micro", result.get().strategyName());
        assertEquals(0, result.get().messagesCompacted()); // message count unchanged

        // Verify content is trimmed
        Message firstMsg = session.messages().get(1); // skip system[0]
        ContentBlock block = firstMsg.contentBlocks().getFirst();
        String content = ((ContentBlock.ToolResultBlock) block).content();
        assertTrue(content.length() < hugeContent.length());
        assertTrue(content.contains("[... "));
        assertTrue(content.contains("chars truncated ...]"));
    }

    @Test
    void leavesTextBlocksUntouched() {
        String text = "normal text message";
        ConversationSession session = newSession(Message.user(text));

        Optional<CompactResult> result = strategy.apply(session, budget, madacode.core.turn.CancellationToken.never());

        assertTrue(result.isEmpty());
        assertEquals(text, extractText(session.messages().get(1)));
    }

    @Test
    void leavesShortToolResultsUntouched() {
        String shortContent = "small result";
        ConversationSession session = newSession(
                Message.user(List.of(new ContentBlock.ToolResultBlock("t1", shortContent, true, -1))));

        Optional<CompactResult> result = strategy.apply(session, budget, madacode.core.turn.CancellationToken.never());

        assertTrue(result.isEmpty());
    }

    @Test
    void truncationPreservesHeadAndTail() {
        String content = "HEAD_START\n" + "m".repeat(8000) + "\nTAIL_END";
        ConversationSession session = newSession(
                Message.user(List.of(new ContentBlock.ToolResultBlock("t1", content, true, -1))));

        strategy.apply(session, budget, madacode.core.turn.CancellationToken.never());

        String truncated = ((ContentBlock.ToolResultBlock)
                session.messages().get(1).contentBlocks().getFirst()).content();
        assertTrue(truncated.startsWith("HEAD_START"));
        assertTrue(truncated.endsWith("TAIL_END"));
    }

    @Test
    void truncationPreservesFailureFlag() {
        // Regression: previously the 2-arg ToolResultBlock ctor defaulted
        // success=true, so truncating a failed tool result silently flipped it
        // to success on the next API round-trip.
        String hugeContent = "x".repeat(10_000);
        ContentBlock.ToolResultBlock failure =
                new ContentBlock.ToolResultBlock("t1", hugeContent, false, 1234);
        ConversationSession session = newSession(Message.user(List.of(failure)));

        Optional<CompactResult> result = strategy.apply(session, budget, madacode.core.turn.CancellationToken.never());
        assertTrue(result.isPresent());

        ContentBlock.ToolResultBlock truncated = (ContentBlock.ToolResultBlock)
                session.messages().get(1).contentBlocks().getFirst();
        assertFalse(truncated.success(), "success=false must survive truncation");
        assertEquals(1234, truncated.durationMs(), "durationMs must survive truncation");
    }

    private static ConversationSession newSession(Message... msgs) {
        return new ConversationSession(
                "test", java.time.Instant.now(), java.nio.file.Path.of("."),
                toList(msgs));
    }

    private static List<Message> toList(Message... msgs) {
        List<Message> list = new java.util.ArrayList<>();
        list.add(Message.system("Init"));
        list.addAll(List.of(msgs));
        return list;
    }

    private static String extractText(Message m) {
        return m.contentBlocks().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .findFirst().orElse("");
    }
}
