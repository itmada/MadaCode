package madacode.cli.slash;

import java.util.Optional;

public interface SlashCommand {
    String name();
    String description();
    String usage();
    SlashAction execute(SlashContext ctx, String args);

    default boolean isEnabled() {
        return true;
    }

    default boolean matches(String command) {
        return name().equalsIgnoreCase(command);
    }

    /**
     * Return an optional argument provider for this command.
     * When present, the SlashComposer shows a completion panel after the
     * command name is entered. Default is empty (no argument completions).
     */
    default Optional<ArgumentProvider> argumentProvider(SlashContext ctx) {
        return Optional.empty();
    }

    default String displayNames() {
        return "/" + name();
    }
}
