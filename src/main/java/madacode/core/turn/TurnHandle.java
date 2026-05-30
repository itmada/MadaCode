package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public record TurnHandle(
        String turnId,
        CompletableFuture<TurnResult> result,
        Consumer<String> cancel) {}
