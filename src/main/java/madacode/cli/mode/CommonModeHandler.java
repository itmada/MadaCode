package madacode.cli.mode;

import madacode.cli.AtFileCompleter;
import madacode.core.session.ConversationSession;
import madacode.core.turn.TurnExecutor;

import java.util.Objects;

/**
 * Preserves the current default REPL behavior for ordinary inputs.
 */
public final class CommonModeHandler implements ModeHandler {

    private final TurnExecutor turnExecutor;

    public CommonModeHandler(TurnExecutor turnExecutor) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
    }

    @Override
    public ModeExecution handle(String line, ConversationSession session) {
        session.addInput(line);
        String expanded = AtFileCompleter.expandMentions(line, session);
        return ModeExecution.managedTurn(turnExecutor.submit(session, expanded));
    }
}
