package madacode.services.compact;

import madacode.core.CancellationToken;
import madacode.core.CancellationException;
import madacode.core.ConversationSession;
import madacode.core.Message;
import madacode.core.MetaEvent;

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

    public boolean planAndApply(ConversationSession session, Consumer<MetaEvent> eventSink,
                                CancellationToken cancellationToken) {
        int est = estimator.estimate(session.messages());
        if (!budget.isOverSoft(est)) return false;

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
                var r = result.get();
                eventSink.accept(new MetaEvent.CompactCompleted(r));
                session.addMessage(Message.system(
                        "[compact] " + (r.beforeTokens() / 1000) + "k → "
                                + (r.afterTokens() / 1000) + "k via " + r.strategyName()
                                + " (" + r.messagesCompacted() + " summarized, "
                                + r.messagesKept() + " kept)"));
                int newEst = estimator.estimate(session.messages());
                if (!budget.isOverSoft(newEst)) return true;
            }
        }

        eventSink.accept(new MetaEvent.CompactFailed(
                "Could not reduce below soft limit after " + strategies.size() + " strategies"));
        return false;
    }

    public boolean forceCompact(ConversationSession session, Consumer<MetaEvent> eventSink,
                                CancellationToken cancellationToken) {
        int est = estimator.estimate(session.messages());
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
                var r = result.get();
                eventSink.accept(new MetaEvent.CompactCompleted(r));
                session.addMessage(Message.system(
                        "[compact] " + (r.beforeTokens() / 1000) + "k → "
                                + (r.afterTokens() / 1000) + "k via " + r.strategyName()
                                + " (" + r.messagesCompacted() + " summarized, "
                                + r.messagesKept() + " kept)"));
                return true;
            }
        }

        eventSink.accept(new MetaEvent.CompactFailed(
                "Could not compact with " + strategies.size() + " strategies"));
        return false;
    }

    private static String cancelledMessage(CancellationToken token, String detail) {
        String reason = token.reason();
        if (reason == null || reason.isBlank()) {
            reason = detail;
        }
        return "Cancelled: " + reason;
    }
}
