package madacode.services.compact;

import madacode.core.turn.CancellationToken;
import madacode.core.turn.CancellationException;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.model.MetaEvent;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class CompactPlanner {

    private final TokenEstimator estimator;
    private final CompactBudget budget;
    private final List<CompactStrategy> strategies;

    public CompactPlanner(TokenEstimator estimator, CompactBudget budget, List<CompactStrategy> strategies) {
        this.estimator = estimator;
        this.budget = budget;
        this.strategies = List.copyOf(strategies);
    }

    public boolean shouldCompact(ConversationSession session) {
        return budget.isOverSoft(estimator.estimate(session.modelContextMessages()));
    }

    /**
     * Automatic compaction: only runs when over the soft limit, and stops as
     * soon as the model context is back under it. Returns {@code true} if it
     * reached the target, {@code false} if the budget was already fine or no
     * combination of strategies could get under the soft limit.
     */
    public boolean planAndApply(ConversationSession session, Consumer<MetaEvent> eventSink,
                                CancellationToken cancellationToken) {
        if (!budget.isOverSoft(estimator.estimate(session.modelContextMessages()))) {
            return false;
        }
        return runStrategies(session, eventSink, cancellationToken, true);
    }

    /**
     * Forced compaction regardless of the current budget: the first strategy
     * that produces a result wins. Used by the manual {@code /compact} command.
     * Returns {@code true} if any strategy compacted, {@code false} otherwise.
     */
    public boolean forceCompact(ConversationSession session, Consumer<MetaEvent> eventSink,
                                CancellationToken cancellationToken) {
        return runStrategies(session, eventSink, cancellationToken, false);
    }

    /**
     * Shared compaction loop for {@link #planAndApply} and {@link #forceCompact}.
     * With {@code stopWhenUnderSoft} the loop keeps applying strategies until the
     * model context drops under the soft limit (automatic); otherwise the first
     * strategy that produces a result wins (forced).
     *
     * <p>Emits exactly one {@code CompactStarted}, one {@code CompactCompleted}
     * per applied strategy, and at most one {@code CompactFailed}. A single
     * invocation therefore never double-reports — callers must not chain a
     * second pass to "retry" a failure.
     */
    private boolean runStrategies(ConversationSession session, Consumer<MetaEvent> eventSink,
                                  CancellationToken cancellationToken, boolean stopWhenUnderSoft) {
        int est = estimator.estimate(session.modelContextMessages());
        eventSink.accept(new MetaEvent.CompactStarted(est, budget.softLimit()));

        for (CompactStrategy strategy : strategies) {
            if (cancellationToken.isCancelled()) {
                eventSink.accept(new MetaEvent.CompactFailed(cancelledMessage(cancellationToken,
                        "before strategy " + strategy.name())));
                return false;
            }
            Optional<CompactResult> result;
            try {
                result = strategy.apply(session, budget, cancellationToken);
            } catch (CancellationException e) {
                eventSink.accept(new MetaEvent.CompactFailed(cancelledMessage(cancellationToken, e.getMessage())));
                return false;
            }
            if (cancellationToken.isCancelled()) {
                eventSink.accept(new MetaEvent.CompactFailed(cancelledMessage(cancellationToken,
                        "during strategy " + strategy.name())));
                return false;
            }
            if (result.isPresent()) {
                emitCompacted(session, eventSink, result.get());
                if (!stopWhenUnderSoft) {
                    return true;
                }
                if (!budget.isOverSoft(estimator.estimate(session.modelContextMessages()))) {
                    return true;
                }
            }
        }

        eventSink.accept(new MetaEvent.CompactFailed(stopWhenUnderSoft
                ? "Could not reduce below soft limit after " + strategies.size() + " strategies"
                : "Could not compact with " + strategies.size() + " strategies"));
        return false;
    }

    private void emitCompacted(ConversationSession session, Consumer<MetaEvent> eventSink, CompactResult r) {
        eventSink.accept(new MetaEvent.CompactCompleted(r));
        session.addMessage(Message.system(
                "[compact] " + (r.beforeTokens() / 1000) + "k → "
                        + (r.afterTokens() / 1000) + "k via " + r.strategyName()
                        + " (" + r.messagesCompacted() + " summarized, "
                        + r.messagesKept() + " kept)"));
    }

    private static String cancelledMessage(CancellationToken token, String detail) {
        String reason = token.reason();
        if (reason == null || reason.isBlank()) {
            reason = detail;
        }
        return "Cancelled: " + reason;
    }
}
