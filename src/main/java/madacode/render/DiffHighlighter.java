package madacode.render;

import madacode.tui.theme.Tk;

import java.util.*;

/**
 * Word-level diff highlighter for unified-diff output from tools.
 * Produces styled {@link DiffLine}s with per-token spans so the
 * terminal renderer can color individual changed words, not just
 * whole lines.
 */
public final class DiffHighlighter {

    private DiffHighlighter() {}

    /**
     * Parse raw unified-diff output and return styled lines.
     * Lines beyond {@code maxLines} are omitted.
     */
    public static List<DiffLine> highlight(String rawDiff, int maxLines) {
        if (rawDiff == null || rawDiff.isBlank()) return List.of();

        List<RawLine> raw = new ArrayList<>();
        for (String line : rawDiff.split("\\R")) {
            if (raw.size() >= maxLines) break;
            raw.add(classify(line));
        }
        return applyWordDiff(raw);
    }

    // ---- types -------------------------------------------------------

    public enum Style { CONTEXT, ADDED, REMOVED, HUNK }

    public record Span(int from, int to, Style style) {}

    public record DiffLine(String text, List<Span> spans) {
        public boolean isEmpty() { return text == null || text.isEmpty(); }
    }

    // ---- line classification ----------------------------------------

    private enum LineKind { ADD, DEL, HUNK, CTX }

    private record RawLine(String text, LineKind kind) {}

    private static RawLine classify(String line) {
        if (line.startsWith("@@")) return new RawLine(line, LineKind.HUNK);
        if (line.startsWith("+") && !line.startsWith("+++")) return new RawLine(line, LineKind.ADD);
        if (line.startsWith("-") && !line.startsWith("---")) return new RawLine(line, LineKind.DEL);
        return new RawLine(line, LineKind.CTX);
    }

    // ---- word-diff engine -------------------------------------------

    private static List<DiffLine> applyWordDiff(List<RawLine> raw) {
        List<DiffLine> out = new ArrayList<>();
        int i = 0;
        while (i < raw.size()) {
            RawLine r = raw.get(i);
            if (r.kind == LineKind.DEL && i + 1 < raw.size() && raw.get(i + 1).kind == LineKind.ADD) {
                // Paired - / +: word-level diff
                RawLine del = r;
                RawLine add = raw.get(i + 1);
                String[] delWords = tokenize(del.text.substring(1));
                String[] addWords = tokenize(add.text.substring(1));
                List<String> lcs = lcs(delWords, addWords);
                out.add(styledLine("- " + del.text.substring(1), delWords, lcs, Style.REMOVED));
                out.add(styledLine("+ " + add.text.substring(1), addWords, lcs, Style.ADDED));
                i += 2;
            } else if (r.kind == LineKind.DEL) {
                // Unpaired deletion
                out.add(fullyStyled(r.text, Style.REMOVED));
                i++;
            } else if (r.kind == LineKind.ADD) {
                // Unpaired addition
                out.add(fullyStyled(r.text, Style.ADDED));
                i++;
            } else if (r.kind == LineKind.HUNK) {
                out.add(new DiffLine(r.text, List.of(new Span(0, r.text.length(), Style.HUNK))));
                i++;
            } else {
                // Context
                out.add(new DiffLine(r.text, List.of()));
                i++;
            }
        }
        return out;
    }

    /** Tokenize on word boundaries; each token includes its leading whitespace. */
    private static String[] tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        for (int j = 0; j < s.length(); j++) {
            if (Character.isWhitespace(s.charAt(j))) {
                if (j > start) tokens.add(s.substring(start, j));
                // gather whitespace
                int ws = j;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                tokens.add(s.substring(ws, j));
                start = j;
                j--; // compensate for loop increment
            }
        }
        if (start < s.length()) tokens.add(s.substring(start));
        return tokens.toArray(new String[0]);
    }

    /** LCS of two token arrays. O(m*n). */
    private static List<String> lcs(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++)
            for (int j = 1; j <= n; j++)
                dp[i][j] = a[i - 1].equals(b[j - 1])
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);

        List<String> result = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 && j > 0) {
            if (a[i - 1].equals(b[j - 1])) {
                result.add(a[i - 1]);
                i--; j--;
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }
        Collections.reverse(result);
        return result;
    }

    /** Build a styled line by walking tokens and LCS in parallel. */
    private static DiffLine styledLine(String raw, String[] tokens, List<String> lcs, Style mismatchStyle) {
        StringBuilder sb = new StringBuilder(raw);
        List<Span> spans = new ArrayList<>();
        Deque<String> common = new ArrayDeque<>(lcs);
        int offset = 2; // skip the "- " / "+ " prefix we added
        for (String token : tokens) {
            Style style;
            if (!common.isEmpty() && common.peekFirst().equals(token)) {
                style = Style.CONTEXT;
                common.pollFirst();
            } else {
                style = mismatchStyle;
            }
            if (style != Style.CONTEXT) {
                spans.add(new Span(offset, offset + token.length(), style));
            }
            offset += token.length();
        }
        return new DiffLine(sb.toString(), spans);
    }

    private static DiffLine fullyStyled(String text, Style style) {
        return new DiffLine(text, List.of(new Span(0, text.length(), style)));
    }

    // ---- rendering ---------------------------------------------------

    /** Render a DiffLine to an ANSI-styled string. */
    public static String render(DiffLine line) {
        if (line.isEmpty()) return "";
        if (line.spans.isEmpty()) return line.text;
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (Span span : line.spans) {
            if (span.from > pos) sb.append(Tk.dim(line.text.substring(pos, span.from)));
            String seg = line.text.substring(span.from, span.to);
            sb.append(switch (span.style) {
                case ADDED   -> Tk.diffAdd(seg);
                case REMOVED -> Tk.diffDel(seg);
                case HUNK    -> Tk.diffHunk(seg);
                case CONTEXT -> seg;
            });
            pos = span.to;
        }
        if (pos < line.text.length()) sb.append(Tk.dim(line.text.substring(pos)));
        return sb.toString();
    }

    /** Convenience: highlight and render in one call. */
    public static List<String> highlightAndRender(String rawDiff, int maxLines) {
        return highlight(rawDiff, maxLines).stream()
                .map(DiffHighlighter::render)
                .toList();
    }
}
