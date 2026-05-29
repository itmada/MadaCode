package madacode.cli.slash;

import madacode.tui.theme.Tk;

final class StatusCommand implements SlashCommand {
    @Override public String name() { return "status"; }
    @Override public String description() { return "Show current REPL status"; }
    @Override public String usage() { return "/status"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        ctx.screen().scrollback(Tk.dim("cwd") + " " + ctx.session().workingDirectory());
        ctx.screen().scrollback(Tk.dim("session") + " " + ctx.session().sessionId()
                + "  " + ctx.session().title());
        ctx.screen().scrollback(Tk.dim("messages") + " " + ctx.session().messages().size());
        String model = ctx.providerRegistry() == null ? "(unknown)" : ctx.providerRegistry().active().currentModel().name();
        ctx.screen().scrollback(Tk.dim("model") + " " + model);
        String mode = ctx.sessionContext() == null ? (ctx.session().isPlanMode() ? "plan" : "auto") : ctx.sessionContext().mode().name().toLowerCase();
        ctx.screen().scrollback(Tk.dim("mode") + " " + mode);
        return new SlashAction.Handled();
    }
}
