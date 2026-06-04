package madacode.cli.slash;

import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningController;

final class LongRunReviseCommand implements SlashCommand {

    private final LongRunningController controller;

    LongRunReviseCommand(LongRunningController controller) {
        this.controller = controller;
    }

    @Override
    public String name() {
        return "longrun-revise";
    }

    @Override
    public String description() {
        return "Return the long-running plan to planning stage for revision";
    }

    @Override
    public String usage() {
        return "/longrun-revise";
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.session().workflowMode() != SessionMode.LONG_RUNNING) {
            ctx.screen().scrollback("Not in long-running mode.");
            return new SlashAction.Handled();
        }
        if (ctx.session().longRunningStage() != LongRunningStage.DRAFT) {
            ctx.screen().scrollback("Cannot revise plan in stage: " + ctx.session().longRunningStage());
            return new SlashAction.Handled();
        }
        try {
            controller.revisePlan(ctx.session());
            ctx.screen().scrollback("Plan remains in DRAFT for revision.");
            return new SlashAction.Handled(true);
        } catch (Exception e) {
            ctx.screen().scrollback("Failed to revise plan: " + e.getMessage());
            return new SlashAction.Handled();
        }
    }
}
