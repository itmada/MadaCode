package madacode.cli.slash;

import madacode.core.session.SessionMode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

final class ModeCommand implements SlashCommand {

    @Override public String name() { return "mode"; }
    @Override public String description() { return "Show or switch the active mode"; }
    @Override public String usage() { return "/mode [strict|normal|plan|all-pass]"; }

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
        mode.applyTo(ctx.session());
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setMode(mode);
        }

        SlashFeedback.muted(ctx.screen(), "Mode set to: " + mode.id());
        if (mode == SessionMode.ALL_PASS) {
            SlashFeedback.muted(ctx.screen(), "Warning: all-pass suppresses interactive approval. "
                    + "Structural safety rules still apply.");
        }
        return new SlashAction.Handled();
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
