package madacode.core.model;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

public final class Message {

    private final MessageRole role;
    private final List<ContentBlock> contentBlocks;

    private Message(MessageRole role, List<ContentBlock> contentBlocks) {
        this.role = Objects.requireNonNull(role, "role");
        this.contentBlocks = List.copyOf(Objects.requireNonNull(contentBlocks, "contentBlocks"));
    }

    public static Message system(String content) {
        return textMessage(MessageRole.SYSTEM, content);
    }

    public static Message user(String content) {
        return textMessage(MessageRole.USER, content);
    }

    public static Message assistant(String content) {
        return textMessage(MessageRole.ASSISTANT, content);
    }

    public static Message assistantTerminal(String message, FinishReason reason) {
        return new Message(MessageRole.ASSISTANT, List.of(new ContentBlock.TerminalBlock(message, reason)));
    }

    public static Message assistant(List<ContentBlock> contentBlocks) {
        return new Message(MessageRole.ASSISTANT, contentBlocks);
    }

    public static Message user(List<ContentBlock> contentBlocks) {
        return new Message(MessageRole.USER, contentBlocks);
    }

    public MessageRole role() {
        return role;
    }

    public String content() {
        return contentBlocks.stream()
                .map(Message::contentBlockToText)
                .collect(Collectors.joining());
    }

    public List<ContentBlock> contentBlocks() {
        return contentBlocks;
    }

    private static String contentBlockToText(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text -> text.text();
            case ContentBlock.TerminalBlock terminal -> terminal.message();
            case ContentBlock.ThinkingBlock thinking -> "";
            case ContentBlock.ToolUseBlock toolUse -> "";
            case ContentBlock.ToolResultBlock toolResult -> toolResult.content();
        };
    }

    private static Message textMessage(MessageRole role, String content) {
        return new Message(role, List.of(new ContentBlock.TextBlock(
                Objects.requireNonNull(content, "content"))));
    }
}
