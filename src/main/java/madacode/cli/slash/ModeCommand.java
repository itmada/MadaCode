package madacode.cli.slash;

import madacode.cli.session.SessionPointer;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.core.session.SessionStorageException;
import madacode.longrunning.LongRunningSessionBootstrap;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

final class ModeCommand implements SlashCommand {

    @Override public String name() { return "mode"; }
    @Override public String description() { return "Show or switch the active workflow mode"; }
    @Override public String usage() { return "/mode [common|long-running]"; }

    @Override
    public Optional<ArgumentProvider> argumentProvider(SlashContext ctx) {
        return Optional.of(partial -> {
            String needle = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
            return Arrays.stream(SessionMode.values())
                    .filter(mode -> mode.id().contains(needle))
                    .map(mode -> new ArgumentProvider.Candidate(mode.id(), mode.description()))
                    .toList();
        });
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        String requested = args.strip();
        if (requested.isBlank()) {
            if (ctx.modeChooser().isPresent()) {
                Optional<String> selected = ctx.modeChooser().get().chooseMode(
                        Arrays.stream(SessionMode.values())
                                .map(SessionMode::id)
                                .toList());
                if (selected.isEmpty()) {
                    SlashFeedback.muted(ctx.screen(), "Mode selection cancelled.");
                    return new SlashAction.Handled();
                }
                requested = selected.get();
            } else {
                listModes(ctx);
                return new SlashAction.Handled();
            }
        }

        Optional<SessionMode> parsed = SessionMode.parse(requested);
        if (parsed.isEmpty()) {
            ctx.screen().scrollback("Unknown mode: " + requested);
            listModes(ctx);
            return new SlashAction.Handled();
        }

        SessionMode mode = parsed.get();
        if (mode == SessionMode.LONG_RUNNING) {
            try {
                ctx.storage().save(ctx.session());
                SlashFeedback.muted(ctx.screen(), "(saved current session)");
            } catch (SessionStorageException e) {
                ctx.screen().scrollback("[warn] Failed to save current session: " + e.getMessage());
            }
            ConversationSession fresh = LongRunningSessionBootstrap.createFreshControlSession(
                    ctx.session().workingDirectory());
            SessionPointer.write(fresh.sessionId());
            SlashFeedback.muted(ctx.screen(), "Entered long-running mode in a fresh control session.");
            SlashFeedback.muted(ctx.screen(),
                    "State starts in DRAFT with an initialized task shell. Discuss scope here before runtime starts execution.");
            SlashFeedback.muted(ctx.screen(),
                    "Current permission is all-pass. Use /permission to change it.");
            return new SlashAction.SwitchToNewLongRunningSession(fresh);
        }

        mode.applyTo(ctx.session());
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setWorkflowMode(mode);
        }
        SlashFeedback.muted(ctx.screen(), "Mode set to: " + mode.id());
        return new SlashAction.Handled(true);
    }

    private static void listModes(SlashContext ctx) {
        SessionMode current = SessionMode.from(ctx.session());
        ctx.screen().scrollback("Modes:");
        for (SessionMode mode : SessionMode.values()) {
            String marker = mode == current ? "*" : " ";
            ctx.screen().scrollback("  " + marker + " " + mode.id() + " - " + mode.description());
        }
    }
}
