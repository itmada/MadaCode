package madacode.cli.editor;

import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EOFError;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.SyntaxError;

import java.util.List;

/**
 * Parser that treats a trailing {@code \} as a line-continuation marker.
 *
 * <p>Pressing Enter after {@code \} shows a secondary prompt; the
 * continuation marker is stripped before the accumulated input is returned
 * to the caller.
 */
public final class ContinuationParser implements Parser {

    @Override
    public ParsedLine parse(String line, int cursor, ParseContext context) throws SyntaxError {
        if (context == ParseContext.ACCEPT_LINE) {
            String last = lastPhysicalLine(line);
            if (last.stripTrailing().endsWith("\\")) {
                throw new EOFError(-1, -1, "continuation");
            }
        }
        // Strip every backslash-newline join from accumulated buffer
        String content = line.replace("\\\n", "");
        int pos = Math.min(cursor, content.length());
        return new SimpleParsedLine(content, pos);
    }

    private static String lastPhysicalLine(String line) {
        int idx = line.lastIndexOf('\n');
        return idx >= 0 ? line.substring(idx + 1) : line;
    }

    private record SimpleParsedLine(String content, int pos) implements CompletingParsedLine {
        @Override public String word()        { return content; }
        @Override public int    wordCursor()  { return pos; }
        @Override public int    wordIndex()   { return 0; }
        @Override public List<String> words() { return List.of(content); }
        @Override public String line()        { return content; }
        @Override public int    cursor()      { return pos; }
        @Override public CharSequence escape(CharSequence s, boolean complete) { return s; }
        @Override public int rawWordCursor()  { return pos; }
        @Override public int rawWordLength()  { return content.length(); }
    }
}
