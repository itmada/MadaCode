package madacode.cli.mode;

import madacode.core.turn.TurnHandle;

import java.util.Objects;

/**
 * Result of routing a plain user input through a workflow-mode handler.
 */
public record ModeExecution(TurnHandle handle, Runnable afterTurn) {

    public ModeExecution {
        Objects.requireNonNull(handle, "handle");
        afterTurn = afterTurn == null ? () -> { } : afterTurn;
    }

    public static ModeExecution managedTurn(TurnHandle handle) {
        return new ModeExecution(handle, () -> { });
    }

    public static ModeExecution managedTurn(TurnHandle handle, Runnable afterTurn) {
        return new ModeExecution(handle, afterTurn);
    }
}
