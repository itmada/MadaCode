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
        String currentModel = registry.active().currentModel().name();

        if (model.isBlank()) {
            if (ctx.modelChooser().isPresent()) {
                Optional<String> selected = ctx.modelChooser().get().chooseModel(
                        SlashChoiceModels.choice(
                                "Model",
                                "Active provider: " + current.name(),
                                current.models().stream().map(Model::name).toList(),
                                currentModel));
                if (selected.isEmpty()) {
                    SlashFeedback.muted(ctx.screen(), "Model selection cancelled.");
                    return new SlashAction.Handled();
                }
                model = selected.get();
            } else {
                ctx.screen().scrollback("Models in provider '" + current.name() + "':");
                current.models().forEach(m -> {
                    String marker = m.name().equals(currentModel) ? "*" : " ";
                    ctx.screen().scrollback("  " + marker + " " + m.name());
                });
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
        SlashFeedback.muted(ctx.screen(), "Model set to: " + model);
        return new SlashAction.Handled();
    }
}
