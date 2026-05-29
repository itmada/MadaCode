package madacode.services.api;

import madacode.core.ContentBlock;
import madacode.core.Message;
import madacode.core.MessageRole;
import madacode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AnthropicMessageSerializer {

    private final ObjectMapper mapper;

    AnthropicMessageSerializer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    String buildRequestBody(
            String model,
            int maxTokens,
            List<Message> messages,
            String systemPrompt,
            Collection<Tool<?>> tools) throws Exception {
        Objects.requireNonNull(model, "model");

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt);
        body.put("stream", true);
        body.set("messages", serializeMessages(messages));

        Collection<Tool<?>> requestTools = tools != null ? tools : List.of();
        if (!requestTools.isEmpty()) {
            ArrayNode toolsArr = mapper.createArrayNode();
            for (Tool<?> tool : requestTools) {
                toolsArr.add(toolDeclaration(tool));
            }
            body.set("tools", toolsArr);
        }
        return mapper.writeValueAsString(body);
    }

    private ArrayNode serializeMessages(List<Message> messages) {
        ArrayNode msgsArr = mapper.createArrayNode();
        for (Message msg : mergeAdjacentNonSystemMessages(messages)) {
            String role = msg.role() == MessageRole.USER ? "user" : "assistant";
            msgsArr.add(messageWithContentBlocks(role, msg));
        }
        return msgsArr;
    }

    private List<Message> mergeAdjacentNonSystemMessages(List<Message> messages) {
        List<Message> merged = new ArrayList<>();
        for (Message message : messages) {
            if (message.role() == MessageRole.SYSTEM) {
                continue;
            }
            if (merged.isEmpty()) {
                merged.add(message);
                continue;
            }
            Message previous = merged.getLast();
            if (previous.role() != message.role()) {
                merged.add(message);
                continue;
            }
            List<ContentBlock> blocks = new ArrayList<>(previous.contentBlocks().size() + message.contentBlocks().size());
            blocks.addAll(previous.contentBlocks());
            blocks.addAll(message.contentBlocks());
            merged.set(merged.size() - 1, previous.role() == MessageRole.USER
                    ? Message.user(blocks)
                    : Message.assistant(blocks));
        }
        return merged;
    }

    private ObjectNode textMessage(String role, String content) {
        ObjectNode m = mapper.createObjectNode();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private ObjectNode messageWithContentBlocks(String role, Message message) {
        List<ContentBlock> blocks = message.contentBlocks();
        if (blocks.size() == 1 && blocks.getFirst() instanceof ContentBlock.TextBlock textBlock) {
            return textMessage(role, textBlock.text());
        }
        ObjectNode m = mapper.createObjectNode();
        m.put("role", role);
        ArrayNode contentArr = mapper.createArrayNode();
        for (ContentBlock block : blocks) {
            contentArr.add(contentBlock(block));
        }
        m.set("content", contentArr);
        return m;
    }

    private ObjectNode contentBlock(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("type", "text");
                n.put("text", text.text());
                yield n;
            }
            case ContentBlock.ThinkingBlock thinking -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("type", "thinking");
                n.put("thinking", thinking.thinking());
                yield n;
            }
            case ContentBlock.ToolUseBlock toolUse -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("type", "tool_use");
                n.put("id", toolUse.id());
                n.put("name", toolUse.name());
                n.set("input", toolUse.input());
                yield n;
            }
            case ContentBlock.ToolResultBlock toolResult -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("type", "tool_result");
                n.put("tool_use_id", toolResult.toolUseId());
                n.put("content", toolResult.content());
                yield n;
            }
        };
    }

    private ObjectNode toolDeclaration(Tool<?> tool) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", tool.name());
        t.put("description", tool.description());
        t.set("input_schema", tool.inputSchema(mapper));
        return t;
    }
}
