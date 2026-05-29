package madacode.cli.completion;

import madacode.cli.slash.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * JLine {@link Completer} that suggests slash commands when the input
 * starts with {@code /}.
 */
public final class SlashCompleter implements Completer {

    private final SlashCommandRegistry registry;

    public SlashCompleter(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String input = line.line().stripLeading();
        if (!input.startsWith("/")) return;
        String prefix = input.substring(1).toLowerCase();
        for (SlashCommandRegistry.PaletteEntry entry : registry.paletteEntries()) {
            String cmd = entry.command(); // "/help", "/model", …
            String name = cmd.substring(1);
            if (name.startsWith(prefix)) {
                candidates.add(new Candidate(
                        cmd, cmd, null, entry.description(),
                        null, null, true));
            }
        }
    }
}
