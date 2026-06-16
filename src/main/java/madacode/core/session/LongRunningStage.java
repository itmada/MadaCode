package madacode.core.session;

import java.util.Optional;

public enum LongRunningStage {
    DRAFT,
    RUNNING,
    INTERRUPT,
    COMPLETED,
    CANCELLED,
    FAILED;

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
            case "COMPLETED" -> Optional.of(COMPLETED);
            case "CANCELLED", "CANCELED" -> Optional.of(CANCELLED);
            case "FAILED" -> Optional.of(FAILED);
            default -> Optional.empty();
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
