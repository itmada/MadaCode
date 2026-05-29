package madacode.cli.slash;

import madacode.core.TokenUsage;

final class CostCommand implements SlashCommand {
    @Override public String name() { return "cost"; }
    @Override public String description() { return "Show token usage"; }
    @Override public String usage() { return "/cost"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        TokenUsage u = ctx.session().tokenUsage();
        String model = ctx.providerRegistry().active().currentModel().name();

        ctx.screen().scrollback("Token usage (model: " + model + "):");
        ctx.screen().scrollback(String.format("  %-14s %d", "input", u.inputTokens()));
        ctx.screen().scrollback(String.format("  %-14s %d", "output", u.outputTokens()));
        ctx.screen().scrollback(String.format("  %-14s %d", "cache read", u.cacheReadTokens()));
        ctx.screen().scrollback(String.format("  %-14s %d", "cache write", u.cacheCreationTokens()));
        return new SlashAction.Handled();
    }
}
