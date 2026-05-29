package madacode.cli;

import madacode.cli.completion.AtFileJLineCompleter;
import madacode.cli.completion.SlashCompleter;
import madacode.cli.editor.ContinuationParser;
import madacode.cli.editor.SessionHistory;
import madacode.cli.slash.SlashCommandRegistry;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.terminal.Terminal;

import java.nio.file.Path;

public final class LineReaderFactory {

    private LineReaderFactory() {}

    public static LineReader create(
            Terminal terminal,
            SlashCommandRegistry slashRegistry,
            SessionHistory sessionHistory,
            Path workingDirectory) {
        SlashCompleter slashCompleter = new SlashCompleter(slashRegistry);
        AtFileJLineCompleter atCompleter = new AtFileJLineCompleter(workingDirectory);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(sessionHistory)
                .completer(new AggregateCompleter(slashCompleter, atCompleter))
                .parser(new ContinuationParser())
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, "  ")
                .variable(LineReader.HISTORY_SIZE, 1000)
                .option(LineReader.Option.AUTO_FRESH_LINE, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.ERASE_LINE_ON_FINISH, true)
                .option(LineReader.Option.INSERT_TAB, false)
                .build();

        bindShiftEnter(reader);
        return reader;
    }

    private static void bindShiftEnter(LineReader reader) {
        reader.getWidgets().put("insert-newline", () -> {
            reader.getBuffer().write('\n');
            return true;
        });
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference("insert-newline"),
                "\033[13;2u",
                "\033[13;5u");
    }
}
