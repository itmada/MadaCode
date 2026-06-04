package madacode.cli.slash;

import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

public final class LongRunContinueCommand implements SlashCommand {

    private static final int DEFAULT_MAX_WORKERS = 5;
    private static final int HARD_MAX_WORKERS = 50;

    @Override public String name() { return "longrun-continue"; }
    @Override public String description() { return "Launch long-running worker cycles for the current task"; }
    @Override public String usage() { return "/longrun-continue [max-workers]"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.session().workflowMode() != SessionMode.LONG_RUNNING) {
            SlashFeedback.muted(ctx.screen(), "Not in long-running mode.");
            return new SlashAction.Handled();
        }
        LongRunningStage stage = ctx.session().longRunningStage();
        if (stage != LongRunningStage.RUNNING) {
            SlashFeedback.muted(ctx.screen(),
                    "Long-running task must be RUNNING to launch workers. Current: " + stage);
            return new SlashAction.Handled();
        }
        if (ctx.session().longRunningTaskId() == null || ctx.session().longRunningTaskId().isBlank()) {
            SlashFeedback.muted(ctx.screen(), "No active long-running task. Approve execution first.");
            return new SlashAction.Handled();
        }
        int maxWorkers = parseMaxWorkers(args);
        if (maxWorkers <= 0) {
            SlashFeedback.muted(ctx.screen(),
                    "max-workers must be between 1 and " + HARD_MAX_WORKERS + ".");
            return new SlashAction.Handled();
        }
        return new SlashAction.LongRunLaunch(maxWorkers);
    }

    private static int parseMaxWorkers(String args) {
        String raw = args == null ? "" : args.strip();
        if (raw.isBlank()) {
            return DEFAULT_MAX_WORKERS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1 || parsed > HARD_MAX_WORKERS) {
                return -1;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
