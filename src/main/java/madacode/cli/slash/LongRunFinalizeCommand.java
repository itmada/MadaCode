package madacode.cli.slash;

import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningController;

final class LongRunFinalizeCommand implements SlashCommand {

    private final LongRunningController controller;

    LongRunFinalizeCommand(LongRunningController controller) {
        this.controller = controller;
    }

    @Override
    public String name() {
        return "longrun-finalize";
    }

    @Override
    public String description() {
        return "Finalize the long-running plan and move to approval stage";
    }

    @Override
    public String usage() {
        return "/longrun-finalize";
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.session().workflowMode() != SessionMode.LONG_RUNNING) {
            ctx.screen().scrollback("Not in long-running mode.");
            return new SlashAction.Handled();
        }
        if (ctx.session().longRunningStage() != madacode.core.session.LongRunningStage.DRAFT) {
            ctx.screen().scrollback("Cannot finalize plan in stage: " + ctx.session().longRunningStage());
            return new SlashAction.Handled();
        }
        try {
            controller.finalizePlan(ctx.session());
            ctx.screen().scrollback("Transition request recorded. Runtime will ask for confirmation before changing state.");
            return new SlashAction.Handled(true);
        } catch (Exception e) {
            ctx.screen().scrollback("Failed to finalize plan: " + e.getMessage());
            return new SlashAction.Handled();
        }
    }
}
