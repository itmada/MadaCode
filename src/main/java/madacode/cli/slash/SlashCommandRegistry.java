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
        commands.add(new ModeCommand());
        commands.add(new PlanCommand());
        commands.add(new PermissionCommand());
        commands.add(new CompactCommand());
        commands.add(new CostCommand());
        commands.add(new StatusCommand());
        commands.add(new ProviderCommand());
        if (skillRegistry != null) {
            commands.add(new SkillsCommand(skillRegistry));
        }
        return new SlashCommandRegistry(
                commands.stream().filter(SlashCommand::isEnabled).toList());
    }

    public List<SlashCommand> commands() {
        return commands;
    }

    public List<SlashCommand> visibleCommands(SlashContext ctx) {
        return commands.stream()
                .filter(command -> command.isVisible(ctx))
                .toList();
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
        return paletteEntries(null);
    }

    public List<PaletteEntry> paletteEntries(SlashContext ctx) {
        List<PaletteEntry> entries = new ArrayList<>();
        List<SlashCommand> source = ctx == null ? commands : visibleCommands(ctx);
        for (SlashCommand command : source) {
            entries.add(new PaletteEntry("/" + command.name(), command.description(ctx)));
        }
        entries.sort(Comparator.comparing(PaletteEntry::command));
        return List.copyOf(entries);
    }

    public record PaletteEntry(String command, String description) {}
}
