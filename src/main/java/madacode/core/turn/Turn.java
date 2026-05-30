package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

import java.security.SecureRandom;
import java.time.Instant;

public record Turn(
        String id,
        String sessionId,
        TurnStatus status,
        String userInput,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        TokenUsage tokenUsage) {

    private static final SecureRandom RNG = new SecureRandom();

    public static Turn create(String sessionId, String input) {
        return new Turn(
                generateTurnId(),
                sessionId,
                TurnStatus.PENDING,
                input,
                Instant.now(),
                null, null,
                TokenUsage.ZERO);
    }

    public Turn withStatus(TurnStatus s) {
        return new Turn(id, sessionId, s, userInput, createdAt, startedAt, finishedAt, tokenUsage);
    }

    public Turn withStarted(Instant t) {
        return new Turn(id, sessionId, status, userInput, createdAt, t, finishedAt, tokenUsage);
    }

    public Turn withFinished(Instant t, TurnStatus terminal) {
        return new Turn(id, sessionId, terminal, userInput, createdAt, startedAt, t, tokenUsage);
    }

    private static String generateTurnId() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("turn_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
