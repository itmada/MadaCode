package madacode.cli.slash;

import java.util.Optional;

final class HelpCommand implements SlashCommand {
    @Override public String name() { return "help"; }
    @Override public String description() { return "Show available commands"; }
    @Override public String usage() { return "/help [command]"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        SlashCommandRegistry registry = ctx.registry();
        if (!args.isBlank()) {
            Optional<SlashCommand> command = registry.find(args);
            if (command.isPresent()) {
                SlashCommand c = command.get();
                ctx.screen().scrollback(c.displayNames() + "  " + c.description());
                ctx.screen().scrollback("Usage: " + c.usage());
            } else {
                ctx.screen().scrollback("Unknown command: /" + args.strip());
            }
            return new SlashAction.Handled();
        }
        ctx.screen().scrollback("Commands:");
        for (SlashCommand command : registry.visibleCommands(ctx)) {
            ctx.screen().scrollback(String.format("  %-20s %s",
                    command.displayNames(), command.description(ctx)));
        }
        return new SlashAction.Handled();
    }
}
