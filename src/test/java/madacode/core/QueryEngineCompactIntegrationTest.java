package madacode.core;

import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.services.compact.CompactBudget;
import madacode.services.compact.CompactPlanner;
import madacode.services.compact.FullCompactStrategy;
import madacode.services.compact.MicroCompactStrategy;
import madacode.services.compact.TokenEstimator;
import madacode.prompt.SystemPromptBuilder;
import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryEngineCompactIntegrationTest {

    @Test
    void compactsLargeTranscriptBeforeCallingMainApi() {
        RecordingApiClient mainApi = new RecordingApiClient("done");
        RecordingApiClient compactApi = new RecordingApiClient("summary of older transcript");
        TokenEstimator estimator = new TokenEstimator();
        CompactPlanner planner = new CompactPlanner(
                estimator,
                new CompactBudget(900, 0.50, 1, 120),
                List.of(
                        new MicroCompactStrategy(estimator),
                        new FullCompactStrategy(compactApi, estimator, e -> {})));
        QueryEngine engine = QueryEngine.builder(
                mainApi,
                new ToolRegistry(),
                new SystemPromptBuilder(),
                PermissionGate.permissive())
                .compactPlanner(planner)
                .build();
        ConversationSession session = largeSession();

        TurnResult result = engine.runTurn(session, "fresh user question");

        assertEquals(FinishReason.COMPLETED, result.finishReason());
        assertEquals(1, compactApi.calls);
        assertEquals(1, mainApi.calls);
        assertTrue(mainApi.firstMessages.stream()
                .anyMatch(m -> m.role() == MessageRole.USER
                        && m.content().contains("CompactBoundary")));
        assertTrue(mainApi.firstMessages.stream()
                .anyMatch(m -> m.role() == MessageRole.USER
                        && m.content().contains("fresh user question")));
    }

    private static ConversationSession largeSession() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("Init"));
        for (int i = 0; i < 8; i++) {
            messages.add(Message.user("old question " + i + " " + "x".repeat(160)));
            messages.add(Message.assistant("old answer " + i + " " + "y".repeat(160)));
        }
        return new ConversationSession("compact-integration", Instant.now(), Path.of("."), messages);
    }

    private static final class RecordingApiClient implements ApiClient {

        private final String responseText;
        private int calls;
        private List<Message> firstMessages = List.of();

        private RecordingApiClient(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public ApiResponse send(
                List<Message> messages,
                String systemPrompt,
                Collection<Tool<?>> tools,
                ApiStreamSink sink,
                CancellationToken cancellationToken) {
            calls++;
            if (firstMessages.isEmpty()) {
                firstMessages = List.copyOf(messages);
            }
            return new ApiResponse(responseText, List.of());
        }
    }
}
