package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

/**
 * A cancellable local operation that should share the normal turn lifecycle
 * without invoking the model loop.
 *
 * <p>Used for long-running slash commands such as {@code /compact}: they need
 * the same per-session serialization, cancellation token, turn logging, crash
 * handling, and persistence as a regular model turn.
 */
@FunctionalInterface
public interface LocalTurnTask {
    TurnResult run(ConversationSession session, CancellationToken token) throws Exception;
}
