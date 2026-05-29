package madacode.cli.slash;

import madacode.cli.session.SessionPointer;
import madacode.core.ConversationSession;
import madacode.core.SessionStorageException;

final class NewCommand implements SlashCommand {
    @Override public String name() { return "new"; }
    @Override public String description() { return "Save current session and start new"; }
    @Override public String usage() { return "/new"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        try {
            ctx.storage().save(ctx.session());
            ctx.screen().scrollback("(saved current session)");
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("[warn] Failed to save current session: " + e.getMessage());
        }
        ConversationSession fresh = new ConversationSession();
        SessionPointer.write(fresh.sessionId());
        ctx.screen().scrollback("New session: " + fresh.sessionId());
        return new SlashAction.SwitchSession(fresh);
    }
}
