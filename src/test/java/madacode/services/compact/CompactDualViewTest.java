package madacode.services.compact;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.turn.CancellationToken;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.tool.VisibleTools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactDualViewTest {

    @Test
    void fullCompactChangesOnlyModelContext() {
        ConversationSession session = new ConversationSession(Path.of("."));
        session.addMessage(Message.user("old request"));
        session.addMessage(Message.assistant("old response"));
        session.addMessage(Message.user("older request"));
        session.addMessage(Message.assistant("older response"));
        session.addMessage(Message.user("recent request"));

        FullCompactStrategy strategy = new FullCompactStrategy(
                new SummaryApiClient("summary"), new TokenEstimator(), event -> {});

        assertTrue(strategy.apply(session, new CompactBudget(10_000, .85, 1, 100),
                CancellationToken.never()).isPresent());
        assertEquals(List.of("Session initialized.", "old request", "old response", "older request",
                        "older response", "recent request"),
                session.transcriptMessages().stream().map(Message::content).toList());
        assertEquals(List.of("Session initialized.",
                        "[CompactBoundary: 4 messages summarized]\nsummary",
                        "recent request"),
                session.modelContextMessages().stream().map(Message::content).toList());
    }

    @Test
    void microCompactKeepsFullToolOutputInTranscript() {
        String output = "x".repeat(200);
        ConversationSession session = new ConversationSession(Path.of("."));
        session.addMessage(Message.user(List.of(
                new ContentBlock.ToolResultBlock("toolu_1", output, true, 1))));

        MicroCompactStrategy strategy = new MicroCompactStrategy(new TokenEstimator());

        assertTrue(strategy.apply(session, new CompactBudget(10_000, .85, 1, 100),
                CancellationToken.never()).isPresent());
        ContentBlock.ToolResultBlock archived = (ContentBlock.ToolResultBlock)
                session.transcriptMessages().getLast().contentBlocks().getFirst();
        ContentBlock.ToolResultBlock compacted = (ContentBlock.ToolResultBlock)
                session.modelContextMessages().getLast().contentBlocks().getFirst();
        assertEquals(output, archived.content());
        assertTrue(compacted.content().contains("chars truncated"));
    }

    private static final class SummaryApiClient implements ApiClient {
        private final String summary;

        private SummaryApiClient(String summary) {
            this.summary = summary;
        }

        @Override
        public ApiResponse send(
                List<Message> messages,
                String systemPrompt,
                VisibleTools tools,
                ApiStreamSink sink,
                CancellationToken cancellationToken) {
            return new ApiResponse(summary, List.of());
        }
    }
}
