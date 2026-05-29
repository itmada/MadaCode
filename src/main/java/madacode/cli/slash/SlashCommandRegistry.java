package madacode.cli.slash;

import madacode.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class SlashCommandRegistry {

    private final List<SlashCommand> commands;

    public SlashCommandRegistry(List<SlashCommand> commands) {
        this.commands = List.copyOf(commands);
    }

    public static SlashCommandRegistry create(SkillRegistry skillRegistry) {
        List<SlashCommand> commands = new ArrayList<>();
        commands.add(new SessionsCommand());
        commands.add(new ResumeCommand());
        commands.add(new NewCommand());
        commands.add(new DeleteCommand());
        commands.add(new HelpCommand());
        commands.add(new ExitCommand());
        commands.add(new ModelCommand());
        commands.add(new CompactCommand());
        commands.add(new ClearCommand());
        commands.add(new CostCommand());
        commands.add(new StatusCommand());
        commands.add(new ThemeCommand());
        commands.add(new ProviderCommand());
        commands.add(new ReplayAllCommand());
        if (skillRegistry != null) {
            commands.add(new SkillsCommand(skillRegistry));
        }
        return new SlashCommandRegistry(
                commands.stream().filter(SlashCommand::isEnabled).toList());
    }

    public List<SlashCommand> commands() {
        return commands;
    }

    public Optional<SlashCommand> find(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return commands.stream().filter(c -> c.matches(lower)).findFirst();
    }

    public List<PaletteEntry> paletteEntries() {
        List<PaletteEntry> entries = new ArrayList<>();
        for (SlashCommand command : commands) {
            entries.add(new PaletteEntry("/" + command.name(), command.description()));
        }
        entries.sort(Comparator.comparing(PaletteEntry::command));
        return List.copyOf(entries);
    }

    public record PaletteEntry(String command, String description) {}
}
