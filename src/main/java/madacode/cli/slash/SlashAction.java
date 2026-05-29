package madacode.cli.slash;

import madacode.core.ConversationSession;
import madacode.core.LocalTurnTask;

public sealed interface SlashAction {
    record Continue() implements SlashAction {}
    record Handled() implements SlashAction {}
    record RunLocalTurn(String label, LocalTurnTask task) implements SlashAction {}
    record SwitchSession(ConversationSession session) implements SlashAction {}
    record ReplayAll() implements SlashAction {}
    record Cleared() implements SlashAction {}
    record Exit() implements SlashAction {}
}
