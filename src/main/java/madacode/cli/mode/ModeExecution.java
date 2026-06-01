package madacode.cli.mode;

import madacode.core.turn.TurnHandle;

import java.util.Objects;

/**
 * Result of routing a plain user input through a workflow-mode handler.
 */
public record ModeExecution(TurnHandle handle) {

    public ModeExecution {
        Objects.requireNonNull(handle, "handle");
    }

    public static ModeExecution managedTurn(TurnHandle handle) {
        return new ModeExecution(handle);
    }
}
