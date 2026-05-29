package madacode.core;

@FunctionalInterface
public interface TurnRunner {
    TurnResult run(Turn turn, ConversationSession session, CancellationToken token)
            throws Exception;
}
