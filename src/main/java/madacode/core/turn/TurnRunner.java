package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

@FunctionalInterface
public interface TurnRunner {
    TurnResult run(Turn turn, ConversationSession session, CancellationToken token)
            throws Exception;
}
