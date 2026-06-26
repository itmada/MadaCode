package madacode.cli;

import madacode.cli.session.SessionChooser;
import madacode.cli.session.SessionPointer;
import madacode.cli.slash.SlashAction;
import madacode.cli.slash.SlashCommand;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.cli.slash.SlashContext;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.session.SessionStorage;
import madacode.provider.ProviderRegistry;
import madacode.services.compact.CompactPlanner;
import madacode.tui.BlockScopedScreen;
import madacode.tui.Screen;
import madacode.tui.widget.NotificationCenter;
import madacode.tui.widget.SessionContext;

import java.util.Objects;
import java.util.Optional;

public class SlashCommandHandler {

    private final SessionStorage storage;
    private final Screen screen;
    private final SessionPointer sessionPointer;
    private final Optional<SessionChooser> sessionChooser;
    private final SlashCommandRegistry registry;
    private final QueryEngine queryEngine;
    private final ProviderRegistry providerRegistry;
    private final CompactPlanner compactPlanner;
    private final SessionContext sessionContext;
    private final Optional<SlashContext.ModelChooser> modelChooser;
    private final Optional<SlashContext.ModeChooser> modeChooser;
    private final Optional<SlashContext.PermissionChooser> permissionChooser;
    private final Optional<SlashContext.ProviderChooser> providerChooser;
    private final NotificationCenter notifications;

    private SlashCommandHandler(Builder builder) {
        this.storage = Objects.requireNonNull(builder.storage, "storage");
        this.screen = Objects.requireNonNull(builder.screen, "screen");
        this.sessionPointer = Objects.requireNonNull(builder.sessionPointer, "sessionPointer");
        this.sessionChooser = Optional.ofNullable(builder.sessionChooser);
        this.registry = Objects.requireNonNull(builder.registry, "registry");
        this.queryEngine = builder.queryEngine;
        this.providerRegistry = builder.providerRegistry;
        this.compactPlanner = builder.compactPlanner;
        this.sessionContext = builder.sessionContext;
        this.modelChooser = Optional.ofNullable(builder.modelChooser);
        this.modeChooser = Optional.ofNullable(builder.modeChooser);
        this.permissionChooser = Optional.ofNullable(builder.permissionChooser);
        this.providerChooser = Optional.ofNullable(builder.providerChooser);
        this.notifications = builder.notifications;
    }

    public static Builder builder(SessionStorage storage, Screen screen) {
        return new Builder(storage, screen);
    }

    public static final class Builder {
        private final SessionStorage storage;
        private final Screen screen;
        private SessionPointer sessionPointer;
        private SessionChooser sessionChooser;
        private SlashCommandRegistry registry;
        private QueryEngine queryEngine;
        private ProviderRegistry providerRegistry;
        private CompactPlanner compactPlanner;
        private SessionContext sessionContext;
        private SlashContext.ModelChooser modelChooser;
        private SlashContext.ModeChooser modeChooser;
        private SlashContext.PermissionChooser permissionChooser;
        private SlashContext.ProviderChooser providerChooser;
        private NotificationCenter notifications;

        private Builder(SessionStorage storage, Screen screen) {
            this.storage = storage;
            this.screen = screen;
        }

        public Builder sessionPointer(SessionPointer sessionPointer) {
            this.sessionPointer = sessionPointer;
            return this;
        }

        public Builder sessionChooser(SessionChooser sessionChooser) {
            this.sessionChooser = sessionChooser;
            return this;
        }

        public Builder registry(SlashCommandRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder queryEngine(QueryEngine queryEngine) {
            this.queryEngine = queryEngine;
            return this;
        }

        public Builder providerRegistry(ProviderRegistry providerRegistry) {
            this.providerRegistry = providerRegistry;
            return this;
        }

        public Builder compactPlanner(CompactPlanner compactPlanner) {
            this.compactPlanner = compactPlanner;
            return this;
        }

        public Builder sessionContext(SessionContext sessionContext) {
            this.sessionContext = sessionContext;
            return this;
        }

        public Builder modelChooser(SlashContext.ModelChooser modelChooser) {
            this.modelChooser = modelChooser;
            return this;
        }

        public Builder modeChooser(SlashContext.ModeChooser modeChooser) {
            this.modeChooser = modeChooser;
            return this;
        }

        public Builder permissionChooser(SlashContext.PermissionChooser permissionChooser) {
            this.permissionChooser = permissionChooser;
            return this;
        }

        public Builder providerChooser(SlashContext.ProviderChooser providerChooser) {
            this.providerChooser = providerChooser;
            return this;
        }

        public Builder notifications(NotificationCenter notifications) {
            this.notifications = notifications;
            return this;
        }

        public SlashCommandHandler build() {
            return new SlashCommandHandler(this);
        }
    }

    public SlashAction handle(String line, ConversationSession current) {
        if (!line.startsWith("/")) {
            return new SlashAction.Continue();
        }

        String[] parts = line.substring(1).strip().split("\\s+", 2);
        String cmd = parts[0].toLowerCase(java.util.Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].strip() : "";
        Optional<SlashCommand> command = registry.find(cmd);
        if (command.isEmpty()) {
            notifyWarn("Unknown command: /" + cmd + " (type /help for commands)");
            return new SlashAction.Handled();
        }

        BlockScopedScreen output = new BlockScopedScreen(screen);
        SlashContext ctx = new SlashContext(
                current,
                output,
                storage,
                registry,
                queryEngine,
                providerRegistry,
                compactPlanner,
                sessionContext,
                sessionPointer,
                sessionChooser,
                modelChooser,
                modeChooser,
                permissionChooser,
                providerChooser);
        try {
            return command.get().execute(ctx, arg);
        } finally {
            output.finishBlock();
        }
    }

    private void notifyWarn(String message) {
        BlockScopedScreen output = new BlockScopedScreen(screen);
        try {
            if (notifications != null) {
                new NotificationCenter(output).warn(message);
            } else {
                output.scrollback(message);
            }
        } finally {
            output.finishBlock();
        }
    }
}
