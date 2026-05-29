package madacode.cli.slash;

import madacode.skill.Skill;
import madacode.skill.SkillRegistry;

import java.util.List;
import java.util.Locale;

public final class SkillsCommand implements SlashCommand {

    private final SkillRegistry registry;

    public SkillsCommand(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "skills"; }
    @Override public String description() { return "List, enable, disable, or reload skills"; }
    @Override public String usage() { return "/skills [list|on <name>|off <name>|reload]"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        String arg = args == null ? "" : args.strip().toLowerCase(Locale.ROOT);
        if (arg.isBlank() || "list".equals(arg)) {
            return list(ctx);
        }
        if (arg.startsWith("on ")) {
            return enable(ctx, arg.substring(3).trim());
        }
        if (arg.startsWith("off ")) {
            return disable(ctx, arg.substring(4).trim());
        }
        if ("reload".equals(arg)) {
            return reload(ctx);
        }
        ctx.screen().scrollback("Usage: " + usage());
        return new SlashAction.Handled();
    }

    private SlashAction list(SlashContext ctx) {
        List<Skill> all = registry.allIncludingDisabled();
        if (all.isEmpty()) {
            ctx.screen().scrollback("No skills loaded. Put SKILL.md files in .mada/skills/<name>/");
            return new SlashAction.Handled();
        }
        ctx.screen().scrollback("Skills:");
        for (Skill s : all) {
            boolean enabled = !registry.stateStore().isDisabled(s.name());
            String src = switch (s.source()) {
                case BUNDLED -> "B";
                case USER -> "U";
                case PROJECT -> "P";
            };
            String status = enabled ? "" : " [off]";
            ctx.screen().scrollback(String.format("  [%s] %-20s %s%s",
                    src, s.name(), s.description(), status));
        }
        return new SlashAction.Handled();
    }

    private SlashAction enable(SlashContext ctx, String name) {
        if (name.isBlank()) {
            ctx.screen().scrollback("Usage: /skills on <name>");
            return new SlashAction.Handled();
        }
        registry.stateStore().enable(name);
        ctx.screen().scrollback("Skill enabled: " + name);
        return new SlashAction.Handled();
    }

    private SlashAction disable(SlashContext ctx, String name) {
        if (name.isBlank()) {
            ctx.screen().scrollback("Usage: /skills off <name>");
            return new SlashAction.Handled();
        }
        registry.stateStore().disable(name);
        ctx.screen().scrollback("Skill disabled: " + name);
        return new SlashAction.Handled();
    }

    private SlashAction reload(SlashContext ctx) {
        registry.reload();
        int total = registry.all().size();
        int enabled = registry.enabled().size();
        ctx.screen().scrollback("Skills reloaded: " + enabled + "/" + total + " enabled");
        return new SlashAction.Handled();
    }
}
