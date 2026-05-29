package madacode.services.compact;

import madacode.core.CancellationToken;
import madacode.core.CancellationException;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.core.ContentBlock;
import madacode.core.ConversationSession;
import madacode.core.Message;
import madacode.core.MessageRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class FullCompactStrategy implements CompactStrategy {

    private static final int MIN_COMPACTABLE = 4;
    private static final int MAX_COMPACT_INPUT_CHARS = 60_000;

    private final ApiClient apiClient;
    private final TokenEstimator estimator;
    private final Consumer<CompactEvent> eventSink;

    public FullCompactStrategy(ApiClient apiClient, TokenEstimator estimator, Consumer<CompactEvent> eventSink) {
        this.apiClient = apiClient;
        this.estimator = estimator;
        this.eventSink = eventSink;
    }

    @Override
    public String name() {
        return "full";
    }

    @Override
    public Optional<CompactResult> apply(ConversationSession session,
                                         CompactBudget budget,
                                         CancellationToken cancellationToken) {
        List<Message> all = session.messages();
        int beforeTokens = estimator.estimate(all);

        // Find split point: from the tail, find the K-th USER message
        // that does NOT contain tool_result blocks (a "real user question")
        int split = findSplitPoint(all, budget.keepRecentRounds());
        if (split <= 1) {
            return Optional.empty();
        }

        List<Message> toCompact = List.copyOf(all.subList(1, split)); // skip initial system
        List<Message> toKeep = List.copyOf(all.subList(split, all.size()));

        if (toCompact.size() < MIN_COMPACTABLE) {
            return Optional.empty();
        }

        eventSink.accept(new CompactEvent.Summarizing(toCompact.size(), split));

        // Render compactable messages as text
        String rendered = renderForCompact(toCompact);
        if (rendered.length() > MAX_COMPACT_INPUT_CHARS) {
            int headLen = MAX_COMPACT_INPUT_CHARS * 3 / 5;
            int tailLen = MAX_COMPACT_INPUT_CHARS * 2 / 5;
            rendered = rendered.substring(0, headLen)
                    + "\n\n[... " + (rendered.length() - headLen - tailLen) + " chars omitted ...]\n\n"
                    + rendered.substring(rendered.length() - tailLen);
        }

        // Call small model to summarize
        String summary;
        try {
            cancellationToken.throwIfCancelled();
            List<Message> request = List.of(Message.user(CompactPrompts.userPrompt(rendered)));
            ApiClient.ApiResponse response = apiClient.send(
                    request, CompactPrompts.SYSTEM, List.of(),
                    new ApiStreamSink() {
                        public void onTextDelta(String chunk) {}
                        public void onToolUseBlock(ContentBlock.ToolUseBlock b) {}
                        public void onThinkingBlock(ContentBlock.ThinkingBlock b) {}
                        public void onMessageStart(String m, madacode.core.TokenUsage u) {}
                        public void onMessageStop(madacode.core.StopReason r, madacode.core.TokenUsage u, long ttft, long total) {}
                    },
                    cancellationToken);
            cancellationToken.throwIfCancelled();
            summary = response.assistantText();
            if (summary == null || summary.isBlank()) {
                return Optional.empty();
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            if (cancellationToken.isCancelled()) {
                throw new CancellationException(cancellationToken.reason());
            }
            return Optional.empty();
        }

        // Build new message list: [system init, compact boundary, ...toKeep]
        List<Message> rebuilt = new ArrayList<>();
        rebuilt.add(all.getFirst()); // system init
        rebuilt.add(Message.user(
                "[CompactBoundary: " + toCompact.size() + " messages summarized]\n" + summary));
        rebuilt.addAll(toKeep);

        int afterTokens = estimator.estimate(rebuilt);
        session.replaceMessages(rebuilt);
        return Optional.of(new CompactResult(
                true, beforeTokens, afterTokens,
                toCompact.size(), toKeep.size() + 1, name()));
    }

    /**
     * From the tail backwards, find the K-th USER message that does NOT
     * contain tool_result blocks. This guarantees tool_use/tool_result
     * pairs are never split across the compact/keep boundary.
     *
     * <p>If the session has fewer than {@code keepRounds} real-user messages
     * — typical of tool-heavy agent loops with sparse user prompts — the
     * search degrades to K-1, K-2, ..., 1 rounds. Keeping fewer recent
     * rounds is preferable to failing entirely: without degradation, deep
     * tool sequences would never compact and the session would grow past
     * the API context limit.
     *
     * @return the index (inclusive) of the split point in {@code messages},
     *         or 0 if there are no real-user messages to anchor a split.
     */
    int findSplitPoint(List<Message> messages, int keepRounds) {
        for (int rounds = Math.max(1, keepRounds); rounds >= 1; rounds--) {
            int found = 0;
            for (int i = messages.size() - 1; i >= 1; i--) { // skip sys[0]
                Message m = messages.get(i);
                if (m.role() == MessageRole.USER && !hasToolResult(m)) {
                    found++;
                    if (found == rounds) {
                        return i;
                    }
                }
            }
        }
        return 0;
    }

    private boolean hasToolResult(Message m) {
        return m.contentBlocks().stream()
                .anyMatch(b -> b instanceof ContentBlock.ToolResultBlock);
    }

    private String renderForCompact(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            String roleLabel = switch (m.role()) {
                case USER -> "[USER]";
                case ASSISTANT -> "[ASSISTANT]";
                case SYSTEM -> "[SYSTEM]";
            };
            sb.append(roleLabel).append('\n');
            for (ContentBlock b : m.contentBlocks()) {
                String text = extractBlockText(b);
                if (!text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String extractBlockText(ContentBlock b) {
        return switch (b) {
            case ContentBlock.TextBlock t -> t.text();
            case ContentBlock.ThinkingBlock t -> t.thinking();
            case ContentBlock.ToolUseBlock u -> "[tool " + u.name() + "]\n"
                    + u.input().toString();
            case ContentBlock.ToolResultBlock r -> "[tool result]\n"
                    + truncateResult(r.content());
        };
    }

    private String truncateResult(String content) {
        return content.length() <= 2000 ? content
                : content.substring(0, 1500) + "\n... (output truncated for summary)";
    }

    public sealed interface CompactEvent {
        record Summarizing(int messageCount, int splitIndex) implements CompactEvent {}
    }
}
