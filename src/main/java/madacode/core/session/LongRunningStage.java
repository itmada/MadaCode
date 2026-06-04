package madacode.core.session;

import java.util.Optional;

public enum LongRunningStage {
    DRAFT,
    RUNNING,
    DONE;

    public LongRunningStage normalized() {
        return this;
    }

    public static Optional<LongRunningStage> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.strip().toUpperCase()) {
            case "DRAFT", "PLANNING", "WAITING_FOR_TASK", "WAITING_FOR_APPROVAL", "INITIALIZING" ->
                    Optional.of(DRAFT);
            case "RUNNING", "EXECUTING" -> Optional.of(RUNNING);
            case "DONE", "COMPLETED", "CANCELLED" -> Optional.of(DONE);
            default -> Optional.empty();
        };
    }
}
