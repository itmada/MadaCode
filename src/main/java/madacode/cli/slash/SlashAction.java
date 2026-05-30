package madacode.cli.slash;

import madacode.core.session.ConversationSession;
import madacode.core.turn.LocalTurnTask;

public sealed interface SlashAction {
    record Continue() implements SlashAction {}
    record Handled() implements SlashAction {}
    record RunLocalTurn(String label, LocalTurnTask task) implements SlashAction {}
    record SwitchSession(ConversationSession session, boolean fresh) implements SlashAction {
        public SwitchSession(ConversationSession session) {
            this(session, false);
        }
    }
    record ReplayAll() implements SlashAction {}
    record Exit() implements SlashAction {}
}
