package madacode.cli.slash;

import madacode.cli.session.SessionChooser;
import madacode.core.ConversationSession;
import madacode.core.QueryEngine;
import madacode.core.SessionStorage;
import madacode.provider.ProviderRegistry;
import madacode.services.compact.CompactPlanner;
import madacode.tui.Screen;
import madacode.tui.widget.SessionContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SlashContext(
        ConversationSession session,
        Screen screen,
        SessionStorage storage,
        SlashCommandRegistry registry,
        QueryEngine queryEngine,
        ProviderRegistry providerRegistry,
        CompactPlanner compactPlanner,
        SessionContext sessionContext,
        Optional<SessionChooser> sessionChooser,
        Optional<ModelChooser> modelChooser,
        Optional<ThemeChooser> themeChooser,
        Optional<ProviderChooser> providerChooser) {

    public SlashContext {
        Objects.requireNonNull(providerRegistry, "providerRegistry");
        sessionChooser = sessionChooser == null ? Optional.empty() : sessionChooser;
        modelChooser = modelChooser == null ? Optional.empty() : modelChooser;
        themeChooser = themeChooser == null ? Optional.empty() : themeChooser;
        providerChooser = providerChooser == null ? Optional.empty() : providerChooser;
    }

    @FunctionalInterface
    public interface ModelChooser {
        Optional<String> chooseModel(List<String> models);
    }

    @FunctionalInterface
    public interface ThemeChooser {
        Optional<String> chooseTheme(List<String> themes);
    }

    @FunctionalInterface
    public interface ProviderChooser {
        Optional<String> chooseProvider(List<String> providers);
    }
}
