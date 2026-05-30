package madacode.cli.slash;

import madacode.cli.session.SessionPointer;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.core.session.SessionStorageException;

import java.util.List;
import java.util.Optional;

final class ResumeCommand implements SlashCommand {

    @Override public String name() { return "resume"; }
    @Override public String description() { return "Switch to session by id prefix"; }
    @Override public String usage() { return "/resume <session-id>"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (args.isBlank()) {
            return interactive(ctx);
        }
        return switchTo(ctx, args);
    }

    private SlashAction interactive(SlashContext ctx) {
        if (ctx.sessionChooser().isEmpty()) {
            ctx.screen().scrollback("Usage: /resume <session-id>");
            return new SlashAction.Handled();
        }
        List<SessionSummary> sessions;
        try {
            sessions = ctx.storage().listSessions();
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("Failed to list sessions: " + e.getMessage());
            return new SlashAction.Handled();
        }
        if (sessions.isEmpty()) {
            ctx.screen().scrollback("No saved sessions.");
            return new SlashAction.Handled();
        }
        Optional<String> selected = ctx.sessionChooser().get().chooseSession(sessions, ctx.session().sessionId());
        if (selected.isEmpty()) {
            ctx.screen().scrollback("Resume cancelled.");
            return new SlashAction.Handled();
        }
        return switchTo(ctx, selected.get());
    }

    private SlashAction switchTo(SlashContext ctx, String id) {
        Optional<ConversationSession> resolved = SessionCommandSupport.resolveById(ctx, id);
        if (resolved.isEmpty()) {
            ctx.screen().scrollback("No session found matching: " + id);
            return new SlashAction.Handled();
        }
        if (resolved.get().sessionId().equals(ctx.session().sessionId())) {
            ctx.screen().scrollback("Already in that session.");
            return new SlashAction.Handled();
        }
        try {
            ctx.storage().save(ctx.session());
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("[warn] Failed to save current session: " + e.getMessage());
        }
        SessionPointer.write(resolved.get().sessionId());
        return new SlashAction.SwitchSession(resolved.get());
    }
}
