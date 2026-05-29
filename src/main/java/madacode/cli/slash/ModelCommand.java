package madacode.cli.slash;


import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;

import java.util.Optional;

final class ModelCommand implements SlashCommand {

    @Override public String name() { return "model"; }
@Override public String description() { return "Show or switch the active model"; }
    @Override public String usage() { return "/model [name]"; }

    @Override
    public Optional<ArgumentProvider> argumentProvider(SlashContext ctx) {
        return Optional.of(partial -> ctx.providerRegistry().active().provider().models().stream()
                .map(Model::name)
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT)
                        .contains(partial.toLowerCase(java.util.Locale.ROOT)))
                .map(n -> new ArgumentProvider.Candidate(n, ""))
                .toList());
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        String model = args.strip();
        ProviderRegistry registry = ctx.providerRegistry();
        Provider current = registry.active().provider();

        if (model.isBlank()) {
            if (ctx.modelChooser().isPresent()) {
                Optional<String> selected = ctx.modelChooser().get().chooseModel(
                        current.models().stream().map(Model::name).toList());
                if (selected.isEmpty()) {
                    ctx.screen().scrollback("Model selection cancelled.");
                    return new SlashAction.Handled();
                }
                model = selected.get();
            } else {
                ctx.screen().scrollback("Models in provider '" + current.name() + "':");
                current.models().forEach(m -> ctx.screen().scrollback("  " + m.name()));
                return new SlashAction.Handled();
            }
        }

        try {
            registry.setActiveModel(model);
        } catch (madacode.provider.ProviderException e) {
            ctx.screen().scrollback(e.getMessage());
            return new SlashAction.Handled();
        }

        if (ctx.sessionContext() != null) {
            Model m = registry.active().currentModel();
            ctx.sessionContext().setModel(m.name());
            ctx.sessionContext().setTokenLimit(m.contextWindow());
        }
        ctx.screen().scrollback("Model set to: " + model);
        return new SlashAction.Handled();
    }
}
