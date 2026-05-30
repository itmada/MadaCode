package madacode.services.compact;

import madacode.core.turn.CancellationToken;
import madacode.core.session.ConversationSession;

import java.util.Optional;

public interface CompactStrategy {

    String name();

    /**
     * Apply this strategy. The {@code cancellationToken} represents the
     * parent (typically a turn) — if the user cancels, in-flight work
     * inside this strategy should propagate the signal (e.g. via a child
     * API call) so the strategy can return quickly. Synchronous, CPU-only
     * strategies may ignore the token.
     */
    Optional<CompactResult> apply(ConversationSession session,
                                  CompactBudget budget,
                                  CancellationToken cancellationToken);
}
