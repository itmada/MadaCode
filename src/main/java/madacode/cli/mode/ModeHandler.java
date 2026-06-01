package madacode.cli.mode;

import madacode.core.session.ConversationSession;

public interface ModeHandler {

    ModeExecution handle(String line, ConversationSession session);
}
