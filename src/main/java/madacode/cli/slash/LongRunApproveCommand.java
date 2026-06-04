package madacode.cli.slash;

import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningController;

final class LongRunApproveCommand implements SlashCommand {

    private final LongRunningController controller;

    LongRunApproveCommand(LongRunningController controller) {
        this.controller = controller;
    }

    @Override
    public String name() {
        return "longrun-approve";
    }

    @Override
    public String description() {
        return "Approve the long-running plan and initialize task execution environment";
    }

    @Override
    public String usage() {
        return "/longrun-approve";
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.session().workflowMode() != SessionMode.LONG_RUNNING) {
            ctx.screen().scrollback("Not in long-running mode.");
            return new SlashAction.Handled();
        }
        if (ctx.session().longRunningStage() != LongRunningStage.DRAFT) {
            ctx.screen().scrollback("Cannot approve: session is in stage "
                    + ctx.session().longRunningStage() + ". Expected DRAFT.");
            return new SlashAction.Handled();
        }
        try {
            controller.approveExecution(ctx.session(), args.isBlank() ? "" : args);
            ctx.screen().scrollback("Transition request recorded. Runtime will ask for confirmation before starting workers.");
            return new SlashAction.Handled(true);
        } catch (Exception e) {
            ctx.screen().scrollback("Failed to approve execution: " + e.getMessage());
            return new SlashAction.Handled();
        }
    }
}
