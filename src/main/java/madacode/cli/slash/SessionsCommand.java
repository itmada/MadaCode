package madacode.cli.slash;

import madacode.core.session.SessionListEntry;
import madacode.core.session.SessionStorage.SessionSummary;
import madacode.core.session.SessionStorageException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class SessionsCommand implements SlashCommand {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override public String name() { return "sessions"; }
    @Override public String description() { return "List all saved sessions"; }
    @Override public String usage() { return "/sessions"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        try {
            List<SessionListEntry> entries = ctx.storage().listEntries();
            if (entries.isEmpty()) {
                ctx.screen().scrollback("No saved sessions.");
            } else {
                ctx.screen().scrollback("Sessions:");
                for (SessionListEntry entry : entries) {
                    switch (entry) {
                        case SessionSummary s ->
                            ctx.screen().scrollback(String.format("  %s  %s  (%d messages)",
                                    DATE_FMT.format(s.lastModifiedAt()),
                                    s.sessionId(),
                                    s.messageCount()));
                        case SessionListEntry.Corrupted c ->
                            ctx.screen().scrollback(String.format("  %s  [corrupted: %s]  %s",
                                    DATE_FMT.format(c.lastModifiedAt()),
                                    c.reason(),
                                    c.path().getFileName()));
                    }
                }
            }
        } catch (SessionStorageException e) {
            ctx.screen().scrollback("Failed to list sessions: " + e.getMessage());
        }
        return new SlashAction.Handled();
    }
}
