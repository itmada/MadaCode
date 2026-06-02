package madacode.cli.slash;

import madacode.core.session.SessionMode;

public final class LongRunContinueCommand implements SlashCommand {

    private static final int DEFAULT_MAX_TURNS = 5;
    private static final int HARD_MAX_TURNS = 50;

    @Override public String name() { return "longrun-continue"; }
    @Override public String description() { return "Continue a long-running execution task for bounded turns"; }
    @Override public String usage() { return "/longrun-continue [max-turns]"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.session().workflowMode() != SessionMode.LONG_RUNNING) {
            SlashFeedback.muted(ctx.screen(), "Not in long-running mode.");
            return new SlashAction.Handled();
        }
        int maxTurns = parseMaxTurns(args);
        if (maxTurns <= 0) {
            SlashFeedback.muted(ctx.screen(), "max-turns must be between 1 and " + HARD_MAX_TURNS + ".");
            return new SlashAction.Handled();
        }
        return new SlashAction.AutoContinue(maxTurns);
    }

    private static int parseMaxTurns(String args) {
        String raw = args == null ? "" : args.strip();
        if (raw.isBlank()) {
            return DEFAULT_MAX_TURNS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1 || parsed > HARD_MAX_TURNS) {
                return -1;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
