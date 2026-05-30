package madacode.cli.slash;

import madacode.tui.theme.Themes;

import java.util.List;
import java.util.Optional;

final class ThemeCommand implements SlashCommand {
    @Override public String name() { return "theme"; }
@Override public String description() { return "Show or switch the terminal theme"; }
    @Override public String usage() { return "/theme [name]"; }

    @Override
    public Optional<ArgumentProvider> argumentProvider(SlashContext ctx) {
        return Optional.of(partial -> Themes.names().stream()
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT)
                        .contains(partial.toLowerCase(java.util.Locale.ROOT)))
                .map(n -> new ArgumentProvider.Candidate(n, ""))
                .toList());
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        String theme = args.strip();
        List<String> names = Themes.names();
        if (theme.isBlank()) {
            if (ctx.themeChooser().isPresent()) {
                Optional<String> selected = ctx.themeChooser().get().chooseTheme(names);
                if (selected.isEmpty()) {
                    SlashFeedback.muted(ctx.screen(), "Theme selection cancelled.");
                    return new SlashAction.Handled();
                }
                theme = selected.get();
            } else {
                ctx.screen().scrollback("Themes:");
                names.forEach(n -> ctx.screen().scrollback("  " + n));
                return new SlashAction.Handled();
            }
        }
        if (!Themes.setActive(theme)) {
            ctx.screen().scrollback("Unknown theme: " + theme);
            return new SlashAction.Handled();
        }
        // SessionContext is now inline — no repaint needed.
        SlashFeedback.muted(ctx.screen(), "Theme set to: " + theme);
        return new SlashAction.Handled();
    }
}
