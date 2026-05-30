package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

public record TurnResult(
        String finalText,
        FinishReason finishReason,
        int iterations) {

    public boolean completed() {
        return finishReason == FinishReason.COMPLETED;
    }
}
