package madacode.core;

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
        ContentBlock.ThinkingBlock,
        ContentBlock.ToolUseBlock,
        ContentBlock.ToolResultBlock {

    record TextBlock(String text) implements ContentBlock {
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
