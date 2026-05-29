package madacode.cli.slash;


final class ExitCommand implements SlashCommand {
    @Override public String name() { return "exit"; }
    @Override public String description() { return "Exit the REPL"; }
    @Override public String usage() { return "/exit"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        return new SlashAction.Exit();
    }
}
