package madacode.cli.mode;

import madacode.core.turn.TurnHandle;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of routing a plain user input through a workflow-mode handler.
 */
public record ModeExecution(TurnHandle handle, AfterTurn afterTurn) {

    public ModeExecution {
        Objects.requireNonNull(handle, "handle");
        afterTurn = afterTurn == null ? Optional::empty : afterTurn;
    }

    @FunctionalInterface
    public interface AfterTurn {
        Optional<ModeExecution> run();
    }

    public static ModeExecution managedTurn(TurnHandle handle) {
        return new ModeExecution(handle, Optional::empty);
    }

    public static ModeExecution managedTurn(TurnHandle handle, AfterTurn afterTurn) {
        return new ModeExecution(handle, afterTurn);
    }
}
