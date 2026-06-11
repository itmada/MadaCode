package madacode.core.model;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

public final class Message {

    private final MessageRole role;
    private final MessageKind kind;
    private final List<ContentBlock> contentBlocks;

    private Message(MessageRole role, MessageKind kind, List<ContentBlock> contentBlocks) {
        this.role = Objects.requireNonNull(role, "role");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.contentBlocks = List.copyOf(Objects.requireNonNull(contentBlocks, "contentBlocks"));
    }

    public static Message system(String content) {
        return textMessage(MessageRole.SYSTEM, MessageKind.STANDARD, content);
    }

    public static Message user(String content) {
        return textMessage(MessageRole.USER, MessageKind.STANDARD, content);
    }

    public static Message assistant(String content) {
        return textMessage(MessageRole.ASSISTANT, MessageKind.STANDARD, content);
    }

    public static Message controllerEvent(String content) {
        return textMessage(MessageRole.USER, MessageKind.CONTROLLER_EVENT, content);
    }

    public static Message assistantTerminal(String message, FinishReason reason) {
        return new Message(
                MessageRole.ASSISTANT,
                MessageKind.STANDARD,
                List.of(new ContentBlock.TerminalBlock(message, reason)));
    }

    public static Message assistant(List<ContentBlock> contentBlocks) {
        return new Message(MessageRole.ASSISTANT, MessageKind.STANDARD, contentBlocks);
    }

    public static Message user(List<ContentBlock> contentBlocks) {
        return new Message(MessageRole.USER, MessageKind.STANDARD, contentBlocks);
    }

    public static Message of(MessageRole role, List<ContentBlock> contentBlocks, MessageKind kind) {
        if (role == MessageRole.SYSTEM && kind != MessageKind.STANDARD) {
            throw new IllegalArgumentException("System messages must use STANDARD kind");
        }
        return new Message(role, kind, contentBlocks);
    }

    public MessageRole role() {
        return role;
    }

    public MessageKind kind() {
        return kind;
    }

    public boolean isControllerEvent() {
        return kind == MessageKind.CONTROLLER_EVENT;
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

    private static Message textMessage(MessageRole role, MessageKind kind, String content) {
        return new Message(role, kind, List.of(new ContentBlock.TextBlock(
                Objects.requireNonNull(content, "content"))));
    }
}
