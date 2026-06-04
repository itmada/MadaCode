package madacode.cli.slash;

import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningController;

final class LongRunCancelCommand implements SlashCommand {

    private final LongRunningController controller;

    LongRunCancelCommand(LongRunningController controller) {
        this.controller = controller;
    }

    @Override
    public String name() {
        return "longrun-cancel";
    }

    @Override
    public String description() {
        return "Cancel the current long-running task";
    }

    @Override
    public String usage() {
        return "/longrun-cancel";
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.session().workflowMode() != SessionMode.LONG_RUNNING) {
            ctx.screen().scrollback("Not in long-running mode.");
            return new SlashAction.Handled();
        }
        LongRunningStage stage = ctx.session().longRunningStage();
        if (stage == LongRunningStage.DONE) {
            ctx.screen().scrollback("Task is already in terminal stage: " + stage);
            return new SlashAction.Handled();
        }
        try {
            controller.cancelTask(ctx.session());
            ctx.screen().scrollback("Transition request recorded. Runtime will ask for confirmation before marking DONE.");
            return new SlashAction.Handled(true);
        } catch (Exception e) {
            ctx.screen().scrollback("Failed to cancel task: " + e.getMessage());
            return new SlashAction.Handled();
        }
    }
}
