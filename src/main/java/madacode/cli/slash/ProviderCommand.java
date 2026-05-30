package madacode.cli.slash;

import madacode.provider.ActiveState;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;

import java.util.Optional;

final class ProviderCommand implements SlashCommand {
    @Override public String name() { return "provider"; }
    @Override public String description() { return "Show or switch the active provider"; }
    @Override public String usage() { return "/provider [name|reset]"; }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        ProviderRegistry registry = ctx.providerRegistry();
        String target = args.strip();

        if (target.isBlank()) {
            if (ctx.providerChooser().isPresent()) {
                Optional<String> selected = ctx.providerChooser().get()
                        .chooseProvider(registry.names());
                if (selected.isEmpty()) {
                    SlashFeedback.muted(ctx.screen(), "Provider selection cancelled.");
                    return new SlashAction.Handled();
                }
                target = selected.get();
            } else {
                listProviders(ctx, registry);
                return new SlashAction.Handled();
            }
        }

        if ("reset".equalsIgnoreCase(target)) {
            registry.resetActive();
            ActiveState state = registry.active();
            syncSessionContext(ctx, state);
            SlashFeedback.muted(ctx.screen(), "Provider reset. Active: " + state.provider().name()
                    + " (model: " + state.currentModel().name() + ")");
            return new SlashAction.Handled();
        }

        if (registry.find(target).isEmpty()) {
            ctx.screen().scrollback("Unknown provider: " + target
                    + ". Available: " + String.join(", ", registry.names()));
            return new SlashAction.Handled();
        }
        registry.setActiveProvider(target);
        ActiveState state = registry.active();
        syncSessionContext(ctx, state);
        SlashFeedback.muted(ctx.screen(), "Provider set to: " + target
                + " (model: " + state.currentModel().name() + ")");
        return new SlashAction.Handled();
    }

    private static void listProviders(SlashContext ctx, ProviderRegistry registry) {
        ActiveState active = registry.active();
        String activeName = active.provider().name();
        for (Provider p : registry.all()) {
            String mark = p.name().equals(activeName) ? "▸ " : "  ";
            String modelInfo = p.name().equals(activeName)
                    ? "current: " + active.currentModel().name()
                    : "default: " + p.defaultModel();
            ctx.screen().scrollback(mark + p.name()
                    + " (" + p.models().size() + " model"
                    + (p.models().size() == 1 ? "" : "s") + ", " + modelInfo + ")");
        }
    }

    private static void syncSessionContext(SlashContext ctx, ActiveState state) {
        if (ctx.sessionContext() == null) return;
        ctx.sessionContext().setModel(state.currentModel().name());
        ctx.sessionContext().setTokenLimit(state.currentModel().contextWindow());
    }
}
