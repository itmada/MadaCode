package madacode.services.compact;

import madacode.core.model.ContentBlock;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MicroCompactStrategy implements CompactStrategy {

    /**
     * Reserved space for the truncation marker so head + marker + tail never
     * exceeds {@code maxChars}. The marker template is fixed except for the
     * digit count of the omitted length (logarithmic), so 50 covers any
     * realistic content size without needing a precise circular calculation.
     */
    private static final int TRUNCATION_MARKER_BUDGET = 50;

    private final TokenEstimator estimator;

    public MicroCompactStrategy(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    @Override
    public String name() {
        return "micro";
    }

    @Override
    public Optional<CompactResult> apply(ConversationSession session,
                                         CompactBudget budget,
                                         madacode.core.turn.CancellationToken cancellationToken) {
        // CPU-only / a few millis — cancellation token ignored intentionally.
        int beforeTokens = estimator.estimate(session.messages());
        List<Message> rewritten = new ArrayList<>();
        boolean changed = false;
        int blocksTruncated = 0;
        int messagesKept = 0;

        for (Message m : session.messages()) {
            List<ContentBlock> blocks = new ArrayList<>();
            boolean messageChanged = false;
            for (ContentBlock b : m.contentBlocks()) {
                ContentBlock next = truncateIfNeeded(b, budget.microMaxResultChars());
                blocks.add(next);
                if (b != next) {
                    changed = true;
                    messageChanged = true;
                    blocksTruncated++;
                }
            }
            if (messageChanged) {
                rewritten.add(rebuildMessage(m, blocks));
            } else {
                rewritten.add(m);
                messagesKept++;
            }
        }

        if (!changed) {
            return Optional.empty();
        }

        session.replaceMessages(rewritten);
        int afterTokens = estimator.estimate(rewritten);
        // Micro truncates tool-result content in place; it does not drop messages.
        // "summarized" = truncated tool-result blocks; "kept" = untouched messages.
        return Optional.of(new CompactResult(
                true, beforeTokens, afterTokens, blocksTruncated, messagesKept, name()));
    }

    private ContentBlock truncateIfNeeded(ContentBlock block, int maxChars) {
        if (!(block instanceof ContentBlock.ToolResultBlock r)) {
            return block;
        }
        String content = r.content();
        if (content.length() <= maxChars) {
            return block;
        }
        // Reserve marker budget first so the final string stays within maxChars.
        // available < maxChars < content.length() here, so head + tail is always
        // strictly less than content.length() — substring bounds are always valid.
        int available = Math.max(0, maxChars - TRUNCATION_MARKER_BUDGET);
        int head = available * 3 / 4;
        int tail = available / 4;
        String truncated = content.substring(0, head)
                + "\n\n[... " + (content.length() - head - tail) + " chars truncated ...]\n\n"
                + content.substring(content.length() - tail);
        return new ContentBlock.ToolResultBlock(r.toolUseId(), truncated, r.success(), r.durationMs());
    }

    private Message rebuildMessage(Message original, List<ContentBlock> newBlocks) {
        return switch (original.role()) {
            case USER, ASSISTANT -> Message.of(original.role(), newBlocks, original.kind());
            case SYSTEM -> Message.system(extractText(newBlocks));
        };
    }

    private String extractText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .findFirst()
                .orElse("");
    }
}
