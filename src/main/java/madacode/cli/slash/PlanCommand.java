package madacode.cli.slash;

import madacode.core.model.MetaEvent;
import madacode.core.session.SessionMode;

import java.util.Locale;
import java.util.Map;

final class PlanCommand implements SlashCommand {
    @Override public String name() { return "plan"; }
    @Override public String description() { return "Toggle Plan Mode"; }
    @Override public String usage() { return "/plan [on|off|status]"; }

    @Override
    public String description(SlashContext ctx) {
        if (ctx == null) {
            return description();
        }
        return ctx.session().isPlanMode()
                ? "Plan Mode: ON - Enter turns OFF"
                : "Plan Mode: OFF - Enter turns ON";
    }

    @Override
    public boolean isVisible(SlashContext ctx) {
        return ctx == null || SessionMode.from(ctx.session()) != SessionMode.LONG_RUNNING;
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        if (SessionMode.from(ctx.session()) == SessionMode.LONG_RUNNING) {
            SlashFeedback.muted(ctx.screen(),
                    "Plan Mode is unavailable in long-running mode; use the DRAFT/INTERRUPT planning flow.");
            return new SlashAction.Handled();
        }

        String requested = args == null ? "" : args.strip().toLowerCase(Locale.ROOT);
        if (requested.isBlank()) {
            return toggle(ctx);
        }
        if (requested.equals("status")) {
            SlashFeedback.muted(ctx.screen(), "Plan Mode: "
                    + (ctx.session().isPlanMode() ? "ON" : "OFF"));
            return new SlashAction.Handled();
        }

        return switch (requested) {
            case "on", "start", "enter" -> enter(ctx);
            case "off", "stop", "exit" -> exit(ctx);
            default -> {
                ctx.screen().scrollback("Unknown plan mode action: " + args.strip());
                ctx.screen().scrollback("Usage: " + usage());
                yield new SlashAction.Handled();
            }
        };
    }

    private static SlashAction toggle(SlashContext ctx) {
        return ctx.session().isPlanMode() ? exit(ctx) : enter(ctx);
    }

    private static SlashAction enter(SlashContext ctx) {
        if (ctx.session().isPlanMode()) {
            SlashFeedback.muted(ctx.screen(), "Plan Mode already active.");
            return new SlashAction.Handled();
        }
        ctx.session().setPlanMode(true);
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setPlanMode(true);
        }
        ctx.session().fireMetaEvent(new MetaEvent.PlanModeEntered());
        ctx.session().addControllerEvent("plan-mode", Map.of(
                "event", "entered",
                "status", "active",
                "owner", "host"));
        return new SlashAction.Handled(true);
    }

    private static SlashAction exit(SlashContext ctx) {
        if (!ctx.session().isPlanMode()) {
            SlashFeedback.muted(ctx.screen(), "Plan Mode already inactive.");
            return new SlashAction.Handled();
        }
        ctx.session().setPlanMode(false);
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setPlanMode(false);
        }
        ctx.session().fireMetaEvent(new MetaEvent.PlanModeExited());
        ctx.session().addControllerEvent("plan-mode", Map.of(
                "event", "exited",
                "status", "inactive",
                "owner", "host"));
        return new SlashAction.Handled(true);
    }
}
