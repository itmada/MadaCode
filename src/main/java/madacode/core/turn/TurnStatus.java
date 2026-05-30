package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

public enum TurnStatus {
    PENDING, RUNNING, DONE, FAILED, CANCELED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELED;
    }
}
