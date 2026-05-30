package madacode.cli.slash;

import madacode.core.model.FinishReason;
import madacode.core.turn.TurnResult;

final class CompactCommand implements SlashCommand {
    @Override public String name() { return "compact"; }
    @Override public String description() { return "Compact the current conversation"; }
    @Override public String usage() { return "/compact [instructions]"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (ctx.compactPlanner() == null) {
            ctx.screen().scrollback("Compaction is not available in this session.");
            return new SlashAction.Handled();
        }

        return new SlashAction.RunLocalTurn("slash:/compact", (session, token) -> {
            boolean changed = ctx.compactPlanner().forceCompact(
                    session, session::fireMetaEvent, token);
            if (token.isCancelled()) {
                return new TurnResult("(Cancelled: " + token.reason() + ")",
                        FinishReason.CANCELLED, 1);
            }
            if (!changed) {
                ctx.screen().scrollback("Nothing compacted.");
            }
            ctx.storage().save(session);
            return new TurnResult(
                    changed ? "Compact complete" : "Nothing compacted.",
                    FinishReason.COMPLETED,
                    1);
        });
    }
}
