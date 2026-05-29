package madacode.cli.slash;

import madacode.core.Message;
import madacode.core.SessionStorageException;

import java.util.List;

final class ClearCommand implements SlashCommand {
    @Override public String name() { return "clear"; }
    @Override public String description() { return "Clear the current session messages"; }
    @Override public String usage() { return "/clear"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        ctx.session().replaceMessages(List.of(Message.system("Session initialized.")));
        ctx.session().plan().clearAll();
        ctx.session().resetTokenUsage();
        ctx.clearScreen().run();
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setTokens(0, 0);
        }
        try {
            ctx.storage().save(ctx.session());
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("[warn] Failed to save transcript: " + e.getMessage());
        }
        ctx.screen().scrollback("Session messages cleared.");
        return new SlashAction.Cleared();
    }
}
