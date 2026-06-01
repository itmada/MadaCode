package madacode.core.session;

import java.util.EnumSet;
import java.util.Optional;

public enum LongRunningStage {
    WAITING_FOR_TASK,
    PLANNING,
    WAITING_FOR_APPROVAL,
    INITIALIZING,
    EXECUTING,
    COMPLETED,
    CANCELLED;

    public boolean allowsIntent(ConversationSession.LongRunningStageUpdateIntent intent) {
        return switch (this) {
            case PLANNING -> EnumSet.of(
                    ConversationSession.LongRunningStageUpdateIntent.FINALIZE_PLAN).contains(intent);
            case WAITING_FOR_APPROVAL -> EnumSet.of(
                    ConversationSession.LongRunningStageUpdateIntent.APPROVE_EXECUTION).contains(intent);
            case WAITING_FOR_TASK, INITIALIZING, EXECUTING, COMPLETED, CANCELLED -> false;
        };
    }

    public static Optional<LongRunningStage> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LongRunningStage.valueOf(value.strip().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
