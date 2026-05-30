package madacode.cli.slash;

import madacode.cli.session.SessionPointer;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionStorageException;

final class NewCommand implements SlashCommand {
    @Override public String name() { return "new"; }
    @Override public String description() { return "Save current session and start new"; }
    @Override public String usage() { return "/new"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        try {
            ctx.storage().save(ctx.session());
            SlashFeedback.muted(ctx.screen(), "(saved current session)");
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("[warn] Failed to save current session: " + e.getMessage());
        }
        ConversationSession fresh = new ConversationSession();
        SessionPointer.write(fresh.sessionId());
        return new SlashAction.SwitchSession(fresh, true);
    }
}
