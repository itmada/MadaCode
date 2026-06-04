package madacode.core.session;

import java.util.Optional;

public enum LongRunningStage {
    DRAFT,
    RUNNING,
    INTERRUPT,
    DONE;

    public LongRunningStage normalized() {
        return this;
    }

    public static Optional<LongRunningStage> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.strip().toUpperCase()) {
            case "DRAFT" -> Optional.of(DRAFT);
            case "RUNNING" -> Optional.of(RUNNING);
            case "INTERRUPT" -> Optional.of(INTERRUPT);
            case "DONE" -> Optional.of(DONE);
            default -> Optional.empty();
        };
    }
}
