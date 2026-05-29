package madacode.services.compact;

import madacode.core.ContentBlock;
import madacode.core.ConversationSession;
import madacode.core.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MicroCompactStrategy implements CompactStrategy {

    private static final String TRUNCATION_MARKER = "[... ";

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
                                         madacode.core.CancellationToken cancellationToken) {
        // CPU-only / a few millis — cancellation token ignored intentionally.
        int beforeTokens = estimator.estimate(session.messages());
        List<Message> rewritten = new ArrayList<>();
        boolean changed = false;

        for (Message m : session.messages()) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock b : m.contentBlocks()) {
                blocks.add(truncateIfNeeded(b, budget.microMaxResultChars()));
                if (b != blocks.getLast()) changed = true;
            }
            rewritten.add(blocks.equals(m.contentBlocks())
                    ? m : rebuildMessage(m, blocks));
        }

        if (!changed) {
            return Optional.empty();
        }

        session.replaceMessages(rewritten);
        int afterTokens = estimator.estimate(rewritten);
        return Optional.of(new CompactResult(true, beforeTokens, afterTokens, 0, 0, name()));
    }

    private ContentBlock truncateIfNeeded(ContentBlock block, int maxChars) {
        if (!(block instanceof ContentBlock.ToolResultBlock r)) {
            return block;
        }
        String content = r.content();
        if (content.length() <= maxChars) {
            return block;
        }
        // Keep head and tail
        int head = maxChars * 3 / 4;
        int tail = maxChars / 4;
        String truncated = content.substring(0, head)
                + "\n\n[... " + (content.length() - head - tail) + " chars truncated ...]\n\n"
                + content.substring(content.length() - tail);
        return new ContentBlock.ToolResultBlock(r.toolUseId(), truncated, r.success(), r.durationMs());
    }

    private Message rebuildMessage(Message original, List<ContentBlock> newBlocks) {
        return switch (original.role()) {
            case USER -> Message.user(newBlocks);
            case ASSISTANT -> Message.assistant(newBlocks);
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
