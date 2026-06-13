package madacode.cli.slash;

import madacode.cli.session.SessionChooser;
import madacode.cli.session.SessionPointer;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.session.SessionStorage;
import madacode.provider.ProviderRegistry;
import madacode.services.compact.CompactPlanner;
import madacode.tui.Screen;
import madacode.tui.widget.ChoicePrompt;
import madacode.tui.widget.SessionContext;

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
        SessionPointer sessionPointer,
        Optional<SessionChooser> sessionChooser,
        Optional<ModelChooser> modelChooser,
        Optional<ModeChooser> modeChooser,
        Optional<PermissionChooser> permissionChooser,
        Optional<ProviderChooser> providerChooser) {

    public SlashContext {
        Objects.requireNonNull(providerRegistry, "providerRegistry");
        Objects.requireNonNull(sessionPointer, "sessionPointer");
        sessionChooser = sessionChooser == null ? Optional.empty() : sessionChooser;
        modelChooser = modelChooser == null ? Optional.empty() : modelChooser;
        modeChooser = modeChooser == null ? Optional.empty() : modeChooser;
        permissionChooser = permissionChooser == null ? Optional.empty() : permissionChooser;
        providerChooser = providerChooser == null ? Optional.empty() : providerChooser;
    }

    @FunctionalInterface
    public interface ModelChooser {
        Optional<String> chooseModel(ChoicePrompt.Model<String> model);
    }

    @FunctionalInterface
    public interface ModeChooser {
        Optional<String> chooseMode(ChoicePrompt.Model<String> model);
    }

    @FunctionalInterface
    public interface PermissionChooser {
        Optional<String> choosePermission(ChoicePrompt.Model<String> model);
    }

    @FunctionalInterface
    public interface ProviderChooser {
        Optional<String> chooseProvider(ChoicePrompt.Model<String> model);
    }
}
