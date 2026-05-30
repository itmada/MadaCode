package madacode.cli.slash;

import madacode.core.session.ConversationSession;
import madacode.core.session.SessionStorageException;

import java.util.Optional;

final class DeleteCommand implements SlashCommand {
    @Override public String name() { return "delete"; }
    @Override public String description() { return "Delete a session (not current)"; }
    @Override public String usage() { return "/delete <session-id>"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (args.isBlank()) {
            ctx.screen().scrollback("Usage: /delete <session-id>");
            return new SlashAction.Handled();
        }
        Optional<ConversationSession> resolved = SessionCommandSupport.resolveById(ctx, args);
        if (resolved.isEmpty()) {
            ctx.screen().scrollback("No session found matching: " + args);
            return new SlashAction.Handled();
        }
        if (resolved.get().sessionId().equals(ctx.session().sessionId())) {
            ctx.screen().scrollback("Cannot delete the current session. Switch to another session first.");
            return new SlashAction.Handled();
        }
        try {
            ctx.storage().delete(resolved.get().sessionId());
            ctx.screen().scrollback("Deleted session: " + resolved.get().sessionId());
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("Failed to delete session: " + e.getMessage());
        }
        return new SlashAction.Handled();
    }
}
