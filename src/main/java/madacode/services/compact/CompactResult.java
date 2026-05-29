package madacode.services.compact;

public record CompactResult(
        boolean changed,
        int beforeTokens,
        int afterTokens,
        int messagesCompacted,
        int messagesKept,
        String strategyName) {
}
