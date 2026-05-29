package madacode.render;

import madacode.tui.TerminalText;
import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)([^*\\n]+?)\\*(?![*\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)]*)\\)");

    private InlineMarkdown() {}

    static Line parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Line.empty();
        }

        List<Run> runs = new ArrayList<>();
        int index = 0;
        while (index < raw.length()) {
            int open = nextUnescaped(raw, '`', index);
            if (open < 0) {
                addNormal(runs, raw.substring(index));
                break;
            }

            int ticks = countRepeated(raw, open, '`');
            int close = closingBackticks(raw, open + ticks, ticks);
            if (close < 0) {
                addNormal(runs, raw.substring(index));
                break;
            }

            addNormal(runs, raw.substring(index, open));
            addRun(runs, raw.substring(open + ticks, close), Style.INLINE_CODE);
            index = close + ticks;
        }

        return compact(runs);
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

    private static void addNormal(List<Run> runs, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        int index = 0;
        while (index < text.length()) {
            Match next = nextInlineMatch(text, index);
            if (next == null) {
                addRun(runs, unescape(text.substring(index)), Style.NORMAL);
                break;
            }
            if (next.start() > index) {
                addRun(runs, unescape(text.substring(index, next.start())), Style.NORMAL);
            }
            addRun(runs, unescape(next.content()), next.style());
            index = next.end();
        }
    }

    private static Match nextInlineMatch(String text, int start) {
        Match best = null;
        best = pickEarlier(best, find(BOLD, text, start, Style.BOLD, 1, 0));
        best = pickEarlier(best, find(STRIKE, text, start, Style.STRIKE, 1, 1));
        best = pickEarlier(best, find(ITALIC, text, start, Style.ITALIC, 1, 2));
        best = pickEarlier(best, find(LINK, text, start, Style.LINK, 1, 3));
        return best;
    }

    private static Match find(Pattern pattern, String text, int start, Style style, int group, int priority) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find(start)) {
            return null;
        }
        return new Match(matcher.start(), matcher.end(), matcher.group(group), style, priority);
    }

    private static Match pickEarlier(Match current, Match candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (candidate.start() < current.start()) {
            return candidate;
        }
        if (candidate.start() == current.start() && candidate.priority() < current.priority()) {
            return candidate;
        }
        return current;
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

    private static int nextUnescaped(String text, char ch, int start) {
        int index = start;
        while (index < text.length()) {
            int found = text.indexOf(ch, index);
            if (found < 0) {
                return -1;
            }
            if (!isEscaped(text, found)) {
                return found;
            }
            index = found + 1;
        }
        return -1;
    }

    private static int closingBackticks(String text, int start, int count) {
        int index = start;
        while (index < text.length()) {
            int found = nextUnescaped(text, '`', index);
            if (found < 0) {
                return -1;
            }
            int ticks = countRepeated(text, found, '`');
            if (ticks == count) {
                return found;
            }
            index = found + ticks;
        }
        return -1;
    }

    private static int countRepeated(String text, int start, char ch) {
        int index = start;
        while (index < text.length() && text.charAt(index) == ch) {
            index++;
        }
        return index - start;
    }

    private static boolean isEscaped(String text, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    private static String unescape(String text) {
        if (text == null || text.indexOf('\\') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if ("\\`*_[\\]()|~".indexOf(next) >= 0) {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private record Match(int start, int end, String content, Style style, int priority) {}

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
