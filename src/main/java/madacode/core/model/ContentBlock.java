package madacode.core.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * Message content block used to preserve provider-level structure.
 *
 * <p>Tool calls and tool results must remain structured across model
 * round-trips; flattening them into plain text loses the relationship between
 * a {@code tool_use} id and its corresponding {@code tool_result}.
 */
public sealed interface ContentBlock permits
        ContentBlock.TextBlock,
        ContentBlock.TerminalBlock,
        ContentBlock.ThinkingBlock,
        ContentBlock.ToolUseBlock,
        ContentBlock.ToolResultBlock {

    record TextBlock(String text) implements ContentBlock {
    }

    /**
     * Terminal assistant outcome persisted in transcript so future model calls
     * retain turn state, but rendered specially instead of as ordinary prose.
     */
    record TerminalBlock(String message, FinishReason reason) implements ContentBlock {
        public TerminalBlock {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Extended-thinking reasoning content — must be echoed back on subsequent turns. */
    record ThinkingBlock(String thinking) implements ContentBlock {
    }

    record ToolUseBlock(String id, String name, ObjectNode input) implements ContentBlock {

        public ToolUseBlock {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            input = Objects.requireNonNull(input, "input").deepCopy();
        }

        @Override
        public ObjectNode input() {
            return input.deepCopy();
        }
    }

    record ToolResultBlock(String toolUseId, String content, boolean success, long durationMs)
            implements ContentBlock {

        public ToolResultBlock {
            if (durationMs < -1) durationMs = -1;
        }
    }
}
