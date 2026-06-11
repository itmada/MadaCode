package madacode.render;

import madacode.tui.TerminalText;
import madacode.tui.theme.Tk;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

final class InlineMarkdown {

    enum Style {
        NORMAL,
        BOLD,
        ITALIC,
        INLINE_CODE,
        STRIKE,
        LINK
    }

    record Run(String text, Style style) {
        Run {
            text = text == null ? "" : text;
            style = style == null ? Style.NORMAL : style;
        }
    }

    record Line(List<Run> runs) {
        Line {
            runs = List.copyOf(runs == null ? List.of() : runs);
        }

        static Line empty() {
            return new Line(List.of(new Run("", Style.NORMAL)));
        }
    }

    private static final Parser INLINE_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
            .build();

    private InlineMarkdown() {}

    static Line parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Line.empty();
        }
        Line line = collectLine(INLINE_PARSER.parse("x " + raw));
        return dropLeadingText(line, 2);
    }

    static List<Line> wrap(Line line, int maxWidth) {
        if (line == null) {
            return List.of(Line.empty());
        }
        if (maxWidth <= 0) {
            return List.of(line);
        }

        List<Line> lines = new ArrayList<>();
        LineBuilder current = new LineBuilder();
        PendingSpace pendingSpace = PendingSpace.none();

        for (Run run : line.runs()) {
            pendingSpace = appendWrappedRun(lines, current, run, pendingSpace, maxWidth);
        }

        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current.toLine());
        }
        return lines;
    }

    static String render(Line line) {
        if (line == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Run run : line.runs()) {
            sb.append(render(run));
        }
        return sb.toString();
    }

    static String plainText(Line line) {
        if (line == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Run run : line.runs()) {
            sb.append(run.text());
        }
        return sb.toString();
    }

    static int displayWidth(Line line) {
        return Tk.displayWidth(plainText(line));
    }

    static int longestUnbreakableWidth(Line line) {
        String text = plainText(line).trim();
        if (text.isEmpty()) {
            return 0;
        }
        int longest = 0;
        for (String part : text.split("\\s+")) {
            if (!part.isEmpty()) {
                longest = Math.max(longest, Tk.displayWidth(part));
            }
        }
        return longest == 0 ? Tk.displayWidth(text) : longest;
    }

    static Line dropLeadingText(Line line, int charCount) {
        if (line == null || charCount <= 0) {
            return line == null ? Line.empty() : line;
        }

        int remaining = charCount;
        List<Run> runs = new ArrayList<>();
        for (Run run : line.runs()) {
            if (remaining <= 0) {
                addRun(runs, run.text(), run.style());
                continue;
            }

            String text = run.text();
            if (remaining >= text.length()) {
                remaining -= text.length();
                continue;
            }

            addRun(runs, text.substring(remaining), run.style());
            remaining = 0;
        }
        return compact(runs);
    }

    static Line collectLine(Node container) {
        List<Run> runs = new ArrayList<>();
        collectRuns(container, runs, Style.NORMAL);
        return compact(runs);
    }

    static List<Line> collectLinesWithBreaks(Node container) {
        List<List<Run>> segments = new ArrayList<>();
        segments.add(new ArrayList<>());
        collectRunsWithBreaks(container, segments);

        List<Line> result = new ArrayList<>(segments.size());
        for (List<Run> segment : segments) {
            result.add(segment.isEmpty() ? Line.empty() : new Line(segment));
        }
        if (result.isEmpty()) {
            result.add(Line.empty());
        }
        return result;
    }

    private static PendingSpace appendWrappedRun(
            List<Line> lines,
            LineBuilder current,
            Run run,
            PendingSpace pendingSpace,
            int maxWidth) {
        String text = run.text();
        if (text.isEmpty()) {
            return pendingSpace;
        }

        List<Token> tokens = tokens(text, run.style());
        for (Token token : tokens) {
            if (token.whitespace()) {
                if (!current.isEmpty()) {
                    pendingSpace = new PendingSpace(true, token.style());
                }
                continue;
            }

            appendWord(lines, current, token.text(), token.style(), pendingSpace, maxWidth);
            pendingSpace = PendingSpace.none();
        }
        return pendingSpace;
    }

    private static void appendWord(
            List<Line> lines,
            LineBuilder current,
            String word,
            Style style,
            PendingSpace pendingSpace,
            int maxWidth) {
        int wordWidth = Tk.displayWidth(word);
        int spaceWidth = pendingSpace.present() && !current.isEmpty() ? 1 : 0;

        if (wordWidth + spaceWidth <= maxWidth - current.width()) {
            if (spaceWidth > 0) {
                current.add(" ", pendingSpace.style());
            }
            current.add(word, style);
            return;
        }

        if (!current.isEmpty()) {
            lines.add(current.toLine());
            current.clear();
            spaceWidth = 0;
        }

        if (wordWidth <= maxWidth) {
            current.add(word, style);
            return;
        }

        hardWrapWord(lines, current, word, style, maxWidth);
    }

    private static void hardWrapWord(
            List<Line> lines,
            LineBuilder current,
            String word,
            Style style,
            int maxWidth) {
        StringBuilder piece = new StringBuilder();
        int width = 0;
        for (int offset = 0; offset < word.length(); ) {
            int end = TerminalText.clusterEnd(word, offset);
            String cluster = word.substring(offset, end);
            int clusterWidth = Math.max(1, Tk.displayWidth(cluster));
            if (width > 0 && width + clusterWidth > maxWidth) {
                current.add(piece.toString(), style);
                lines.add(current.toLine());
                current.clear();
                piece.setLength(0);
                width = 0;
            }
            piece.append(cluster);
            width += clusterWidth;
            offset = end;
        }
        if (!piece.isEmpty()) {
            current.add(piece.toString(), style);
        }
    }

    private static List<Token> tokens(String text, Style style) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWhitespace = false;
        for (int offset = 0; offset < text.length(); ) {
            int end = TerminalText.clusterEnd(text, offset);
            String cluster = text.substring(offset, end);
            boolean whitespace = cluster.codePoints().allMatch(Character::isWhitespace);
            if (word.isEmpty()) {
                inWhitespace = whitespace;
                word.append(cluster);
            } else if (whitespace == inWhitespace) {
                word.append(cluster);
            } else {
                tokens.add(new Token(word.toString(), inWhitespace, style));
                word.setLength(0);
                inWhitespace = whitespace;
                word.append(cluster);
            }
            offset = end;
        }
        if (!word.isEmpty()) {
            tokens.add(new Token(word.toString(), inWhitespace, style));
        }
        return tokens;
    }

    private static String render(Run run) {
        return switch (run.style()) {
            case NORMAL -> run.text();
            case BOLD -> Tk.bold(run.text());
            case ITALIC -> Tk.italic(run.text());
            case INLINE_CODE -> Tk.inlineCode(run.text());
            case STRIKE -> Tk.dim(run.text());
            case LINK -> Tk.link(run.text());
        };
    }

    private static Line compact(List<Run> input) {
        if (input.isEmpty()) {
            return Line.empty();
        }
        List<Run> runs = new ArrayList<>();
        for (Run run : input) {
            addRun(runs, run.text(), run.style());
        }
        return new Line(runs.isEmpty() ? List.of(new Run("", Style.NORMAL)) : runs);
    }

    private static void collectRuns(Node parent, List<Run> runs, Style style) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                addRun(runs, text.getLiteral(), style);
            } else if (child instanceof Code code) {
                addRun(runs, code.getLiteral(), Style.INLINE_CODE);
            } else if (child instanceof Emphasis) {
                collectRuns(child, runs, Style.ITALIC);
            } else if (child instanceof StrongEmphasis) {
                collectRuns(child, runs, Style.BOLD);
            } else if (child instanceof Strikethrough) {
                collectRuns(child, runs, Style.STRIKE);
            } else if (child instanceof Link link) {
                addRun(runs, collectPlainText(link), Style.LINK);
            } else if (child instanceof Image image) {
                String alt = collectPlainText(image);
                addRun(runs, alt, Style.NORMAL);
                String url = image.getDestination();
                if (url != null && !url.isEmpty()) {
                    addRun(runs, "(" + url + ")", Style.NORMAL);
                }
            } else if (child instanceof HtmlInline html) {
                addRun(runs, html.getLiteral(), Style.NORMAL);
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                addRun(runs, " ", style);
            } else {
                collectRuns(child, runs, style);
            }
        }
    }

    private static void collectRunsWithBreaks(Node parent, List<List<Run>> segments) {
        List<Run> current = segments.getLast();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                addRun(current, text.getLiteral(), Style.NORMAL);
            } else if (child instanceof Code code) {
                addRun(current, code.getLiteral(), Style.INLINE_CODE);
            } else if (child instanceof Emphasis) {
                List<Run> nested = new ArrayList<>();
                collectRuns(child, nested, Style.ITALIC);
                current.addAll(nested);
            } else if (child instanceof StrongEmphasis) {
                List<Run> nested = new ArrayList<>();
                collectRuns(child, nested, Style.BOLD);
                current.addAll(nested);
            } else if (child instanceof Strikethrough) {
                List<Run> nested = new ArrayList<>();
                collectRuns(child, nested, Style.STRIKE);
                current.addAll(nested);
            } else if (child instanceof Link link) {
                addRun(current, collectPlainText(link), Style.LINK);
            } else if (child instanceof HtmlInline html) {
                String literal = html.getLiteral().strip();
                if (literal.equalsIgnoreCase("<br>") || literal.equalsIgnoreCase("<br/>") || literal.equalsIgnoreCase("<br />")) {
                    segments.add(new ArrayList<>());
                    current = segments.getLast();
                } else {
                    addRun(current, html.getLiteral(), Style.NORMAL);
                }
            } else if (child instanceof SoftLineBreak) {
                addRun(current, " ", Style.NORMAL);
            } else if (child instanceof HardLineBreak) {
                segments.add(new ArrayList<>());
                current = segments.getLast();
            } else {
                collectRuns(child, current, Style.NORMAL);
            }
        }
    }

    private static void addRun(List<Run> runs, String text, Style style) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!runs.isEmpty()) {
            Run last = runs.getLast();
            if (last.style() == style) {
                runs.set(runs.size() - 1, new Run(last.text() + text, style));
                return;
            }
        }
        runs.add(new Run(text, style));
    }

    private static String collectPlainText(Node parent) {
        StringBuilder sb = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                sb.append(text.getLiteral());
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else {
                sb.append(collectPlainText(child));
            }
        }
        return sb.toString();
    }

    private record Token(String text, boolean whitespace, Style style) {}

    private record PendingSpace(boolean present, Style style) {
        static PendingSpace none() {
            return new PendingSpace(false, Style.NORMAL);
        }
    }

    private static final class LineBuilder {
        private final List<Run> runs = new ArrayList<>();
        private int width;

        void add(String text, Style style) {
            addRun(runs, text, style);
            width += Tk.displayWidth(text);
        }

        boolean isEmpty() {
            return width == 0 && runs.isEmpty();
        }

        int width() {
            return width;
        }

        void clear() {
            runs.clear();
            width = 0;
        }

        Line toLine() {
            return new Line(runs.isEmpty() ? List.of(new Run("", Style.NORMAL)) : new ArrayList<>(runs));
        }
    }
}
