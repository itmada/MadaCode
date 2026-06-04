package madacode.core.session;

import java.util.Optional;

public enum LongRunningStage {
    DRAFT,
    RUNNING,
    DONE;

    @Deprecated(forRemoval = false)
    public static final LongRunningStage WAITING_FOR_TASK = DRAFT;
    @Deprecated(forRemoval = false)
    public static final LongRunningStage PLANNING = DRAFT;
    @Deprecated(forRemoval = false)
    public static final LongRunningStage WAITING_FOR_APPROVAL = DRAFT;
    @Deprecated(forRemoval = false)
    public static final LongRunningStage INITIALIZING = RUNNING;
    @Deprecated(forRemoval = false)
    public static final LongRunningStage EXECUTING = RUNNING;
    @Deprecated(forRemoval = false)
    public static final LongRunningStage COMPLETED = DONE;
    @Deprecated(forRemoval = false)
    public static final LongRunningStage CANCELLED = DONE;

    public static Optional<LongRunningStage> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toUpperCase();
        return switch (normalized) {
            case "DRAFT", "WAITING_FOR_TASK", "PLANNING", "WAITING_FOR_APPROVAL" ->
                    Optional.of(DRAFT);
            case "RUNNING", "INITIALIZING", "EXECUTING" ->
                    Optional.of(RUNNING);
            case "DONE", "COMPLETED", "CANCELLED" ->
                    Optional.of(DONE);
            default -> Optional.empty();
        };
    }
}
