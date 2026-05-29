package madacode.cli.slash;


final class ReplayAllCommand implements SlashCommand {
    @Override public String name() { return "replay-all"; }
    @Override public String description() { return "Replay the full current conversation"; }
    @Override public String usage() { return "/replay-all"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        return new SlashAction.ReplayAll();
    }
}
