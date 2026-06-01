package madacode.cli.slash;

import madacode.cli.session.SessionChooser;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.session.SessionStorage;
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
        Optional<ModeChooser> modeChooser,
        Optional<PermissionChooser> permissionChooser,
        Optional<ThemeChooser> themeChooser,
        Optional<ProviderChooser> providerChooser) {

    public SlashContext {
        Objects.requireNonNull(providerRegistry, "providerRegistry");
        sessionChooser = sessionChooser == null ? Optional.empty() : sessionChooser;
        modelChooser = modelChooser == null ? Optional.empty() : modelChooser;
        modeChooser = modeChooser == null ? Optional.empty() : modeChooser;
        permissionChooser = permissionChooser == null ? Optional.empty() : permissionChooser;
        themeChooser = themeChooser == null ? Optional.empty() : themeChooser;
        providerChooser = providerChooser == null ? Optional.empty() : providerChooser;
    }

    @FunctionalInterface
    public interface ModelChooser {
        Optional<String> chooseModel(List<String> models);
    }

    @FunctionalInterface
    public interface ModeChooser {
        Optional<String> chooseMode(List<String> modes);
    }

    @FunctionalInterface
    public interface PermissionChooser {
        Optional<String> choosePermission(List<String> permissions);
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
