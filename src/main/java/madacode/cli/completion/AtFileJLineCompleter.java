package madacode.cli.completion;

import madacode.cli.AtFileCompleter;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.nio.file.Path;
import java.util.List;

/**
 * JLine {@link Completer} that suggests workspace files when the user types {@code @}.
 */
public final class AtFileJLineCompleter implements Completer {

    private final Path workingDirectory;

    public AtFileJLineCompleter(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        int atIdx = word.indexOf('@');
        if (atIdx < 0) return;

        String prefix = word.substring(atIdx + 1);
        List<AtFileCompleter.Suggestion> suggestions =
                AtFileCompleter.suggestions(workingDirectory, prefix, AtFileCompleter.DEFAULT_LIMIT);

        for (AtFileCompleter.Suggestion s : suggestions) {
            candidates.add(new Candidate(
                    "@" + s.relativePath(),
                    "@" + s.relativePath(),
                    null, s.directory() ? "directory" : "file",
                    null, null, !s.directory()));
        }
    }
}
