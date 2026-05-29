package madacode.services.compact;

import madacode.core.ContentBlock;
import madacode.core.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class TokenEstimator {

    private static final double BYTES_PER_TOKEN = 3.8;
    private static final int OVERHEAD_PER_BLOCK = 8;
    private static final int OVERHEAD_PER_MESSAGE = 12;

    public int estimate(List<Message> messages) {
        return messages.stream().mapToInt(this::estimate).sum();
    }

    public int estimate(Message message) {
        int sum = OVERHEAD_PER_MESSAGE;
        for (ContentBlock block : message.contentBlocks()) {
            sum += estimate(block);
        }
        return sum;
    }

    public int estimate(ContentBlock block) {
        int payloadBytes = switch (block) {
            case ContentBlock.TextBlock t -> utf8Len(t.text());
            case ContentBlock.ThinkingBlock t -> utf8Len(t.thinking());
            case ContentBlock.ToolUseBlock u -> utf8Len(u.input().toString()) + utf8Len(u.name());
            case ContentBlock.ToolResultBlock r -> utf8Len(r.content());
        };
        return OVERHEAD_PER_BLOCK + (int) Math.ceil(payloadBytes / BYTES_PER_TOKEN);
    }

    private int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
