package madacode.tui.widget;

import madacode.tui.TerminalText;
import madacode.tui.theme.Themes;
import madacode.tui.theme.Token;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure renderer for the slash compose palette.
 *
 * <p>Produces {@link AttributedString} lines embedding the input buffer with
 * cursor position, a candidate list, and footer hints.
 */
public final class CommandPalettePanel {

    private CommandPalettePanel() {}

    /**
     * View model for the compose palette.
     *
     * @param title       panel title (e.g. "Commands" or "/model")
     * @param input       full input text including leading "/"
     * @param cursor      cursor position within {@code input}
     * @param candidates  filtered candidates
     * @param selected    index of highlighted candidate (0-based, -1 if none)
     * @param footer      key hint line
     */
    public record View(
            String title,
            String input,
            int cursor,
            List<PaletteCandidate> candidates,
            int selected,
            String footer,
            boolean showSecondary) {

        public View(
                String title,
                String input,
                int cursor,
                List<PaletteCandidate> candidates,
                int selected,
                String footer) {
            this(title, input, cursor, candidates, selected, footer, false);
        }

        public View {
            title = Objects.requireNonNullElse(title, "");
            input = Objects.requireNonNullElse(input, "");
            cursor = Math.clamp(cursor, 0, input.length());
            candidates = List.copyOf(Objects.requireNonNullElse(candidates, List.of()));
            selected = candidates.isEmpty() ? -1 : Math.clamp(selected, 0, candidates.size() - 1);
            footer = Objects.requireNonNullElse(footer, "");
        }
    }

    public record PaletteCandidate(String primary, String secondary) {
        public PaletteCandidate {
            primary = Objects.requireNonNullElse(primary, "");
            secondary = Objects.requireNonNullElse(secondary, "");
        }

        public PaletteCandidate(String primary) {
            this(primary, "");
        }
    }

    // ---- render ---------------------------------------------------------

    public static List<AttributedString> render(View view, int width) {
        int w = Math.max(1, width);
        List<AttributedString> lines = new ArrayList<>();

        lines.add(dividerLine(view.title(), w));

        lines.add(inputLine(view.input(), view.cursor(), w));

        int primaryWidth = maxPrimaryWidth(view.candidates());
        for (int i = 0; i < view.candidates().size(); i++) {
            PaletteCandidate c = view.candidates().get(i);
            boolean sel = i == view.selected();
            lines.add(candidateLine(c.primary(), c.secondary(), sel, view.showSecondary(), primaryWidth, w));
        }

        if (!view.footer().isBlank()) {
            lines.add(footerLine(view.footer(), w));
        }

        return lines;
    }

    // ---- per-line builders ---------------------------------------------

    private static AttributedString dividerLine(String title, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 0) {
            return AttributedString.EMPTY;
        }
        if (width == 1) {
            b.append("─");
            return b.toAttributedString();
        }

        String prefix = "── ";
        b.append(prefix);
        String visibleTitle = fit(title, Math.max(0, width - TerminalText.displayWidth(prefix) - 1));
        b.style(AttributedStyle.BOLD);
        b.append(visibleTitle);
        style(b, Token.MUTED);
        int used = TerminalText.displayWidth(prefix) + TerminalText.displayWidth(visibleTitle);
        if (used < width) {
            b.append(" ");
            used++;
        }
        if (used < width) {
            b.append("─".repeat(width - used));
        }

        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString inputLine(String input, int cursor, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        if (width <= 0) {
            return AttributedString.EMPTY;
        }
        if (width == 1) {
            style(b, Token.SELECTION);
            b.append(cursor == input.length() ? " " : fitEnd(input, 1));
            b.style(AttributedStyle.DEFAULT);
            return fitLine(b, width);
        }

        Viewport viewport = viewport(input, cursor, width);
        String visible = viewport.text();
        b.style(AttributedStyle.DEFAULT);
        int clampedCursor = viewport.cursorOffset();
        for (int i = 0; i < visible.length(); i++) {
            if (i == clampedCursor) {
                style(b, Token.SELECTION);
            } else {
                b.style(AttributedStyle.DEFAULT);
            }
            b.append(visible.charAt(i));
        }
        if (clampedCursor >= visible.length()) {
            style(b, Token.SELECTION);
            b.append(' ');
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString candidateLine(String primary, String secondary,
                                                   boolean selected,
                                                   boolean showSecondary,
                                                   int primaryWidth,
                                                   int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        String prefix = selected ? "❯ " : "  ";
        style(b, selected ? Token.ACCENT : Token.MUTED);
        b.append(prefix);
        int budget = Math.max(0, width - TerminalText.displayWidth(prefix));
        appendPrimary(b, primary, selected, Math.min(budget, primaryWidth));
        if (showSecondary) {
            appendSecondary(b, primary, secondary, primaryWidth, budget);
        }
        if (width == 1) {
            return new AttributedString(selected ? "❯" : " ");
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString footerLine(String footer, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append(fit(footer, width));
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    // ---- helpers -------------------------------------------------------

    private static String fit(String value, int columns) {
        return TerminalText.fitEnd(value, Math.max(0, columns));
    }

    private static String fitEnd(String value, int columns) {
        return TerminalText.fitEnd(value, Math.max(0, columns));
    }

    static Viewport viewport(String input, int cursor, int width) {
        int safeWidth = Math.max(1, width);
        String value = Objects.requireNonNullElse(input, "");
        int clampedCursor = Math.max(0, Math.min(cursor, value.length()));
        boolean cursorAtEnd = clampedCursor == value.length();
        int textBudget = cursorAtEnd ? Math.max(0, safeWidth - 1) : safeWidth;
        if (safeWidth == 1) {
            String single = cursorAtEnd ? "" : fitEnd(value.substring(clampedCursor), 1);
            return new Viewport(single, Math.min(clampedCursor, single.length()));
        }
        if (TerminalText.displayWidth(value) <= textBudget) {
            return new Viewport(value, clampedCursor);
        }

        List<Cluster> clusters = clusters(value);
        int cursorCluster = clusterIndexForOffset(clusters, clampedCursor);
        int start = 0;
        int end = clusters.size();

        while (windowWidth(clusters, start, end) > textBudget) {
            int leftSpace = cursorCluster - start;
            int rightSpace = end - cursorCluster;
            if (rightSpace > leftSpace && end - 1 > cursorCluster) {
                end--;
            } else if (start < cursorCluster) {
                start++;
            } else if (end - 1 > cursorCluster) {
                end--;
            } else {
                break;
            }
        }

        StringBuilder text = new StringBuilder();
        for (int i = start; i < end; i++) {
            text.append(clusters.get(i).text());
        }
        int windowStartOffset = clusters.isEmpty() || start >= clusters.size() ? value.length() : clusters.get(start).start();
        int cursorOffset = Math.max(0, clampedCursor - windowStartOffset);
        if (cursorOffset > text.length()) {
            cursorOffset = text.length();
        }
        if (cursorOffset == text.length()
                && TerminalText.displayWidth(text.toString()) > textBudget) {
            String trimmed = takeFromEnd(text.toString(), textBudget);
            return new Viewport(trimmed, trimmed.length());
        }
        return new Viewport(text.toString(), cursorOffset);
    }

    private static List<Cluster> clusters(String value) {
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < value.length(); ) {
            int end = TerminalText.clusterEnd(value, i);
            String text = value.substring(i, end);
            clusters.add(new Cluster(i, end, text, Math.max(0, TerminalText.displayWidth(text))));
            i = end;
        }
        return clusters;
    }

    private static int clusterIndexForOffset(List<Cluster> clusters, int offset) {
        for (int i = 0; i < clusters.size(); i++) {
            Cluster cluster = clusters.get(i);
            if (offset <= cluster.start()) {
                return i;
            }
            if (offset < cluster.end()) {
                return i;
            }
        }
        return clusters.size();
    }

    private static int windowWidth(List<Cluster> clusters, int start, int end) {
        int width = 0;
        for (int i = start; i < end; i++) {
            width += clusters.get(i).width();
        }
        return width;
    }

    private static String takeFromEnd(String value, int columns) {
        if (columns <= 0 || value.isEmpty()) {
            return "";
        }
        List<Cluster> clusters = clusters(value);
        int used = 0;
        int start = clusters.size();
        while (start > 0) {
            int next = clusters.get(start - 1).width();
            if (used + next > columns) {
                break;
            }
            used += next;
            start--;
        }
        StringBuilder result = new StringBuilder();
        for (int i = start; i < clusters.size(); i++) {
            result.append(clusters.get(i).text());
        }
        return result.toString();
    }

    private static AttributedString fitLine(AttributedStringBuilder b, int width) {
        String plain = b.toAttributedString().toString();
        int displayWidth = TerminalText.displayWidth(plain);
        if (displayWidth <= width) {
            return b.toAttributedString();
        }
        return new AttributedString(TerminalText.fitEnd(plain, width));
    }

    private static void appendSecondary(
            AttributedStringBuilder b,
            String primary,
            String secondary,
            int primaryWidth,
            int lineBudget) {
        if (secondary == null || secondary.isBlank()) {
            return;
        }
        int namePad = Math.max(0, primaryWidth - TerminalText.displayWidth(primary));
        int remaining = lineBudget - primaryWidth
                - TerminalText.displayWidth("   ");
        if (remaining <= 0) {
            return;
        }
        b.append(" ".repeat(namePad));
        style(b, secondary.startsWith("current:") ? Token.SUCCESS : Token.MUTED);
        b.append("  ");
        b.append(fitEnd(secondary, remaining));
    }

    private static int maxPrimaryWidth(List<PaletteCandidate> candidates) {
        int max = 0;
        for (PaletteCandidate candidate : candidates) {
            max = Math.max(max, TerminalText.displayWidth(candidate.primary()));
        }
        return max;
    }

    private static void appendPrimary(
            AttributedStringBuilder b,
            String primary,
            boolean selected,
            int budget) {
        String visible = fitEnd(primary, budget);
        if (selected) {
            b.style(Themes.active().styleOf(Token.ACCENT).bold());
        } else {
            b.style(AttributedStyle.DEFAULT);
        }
        b.append(visible);
    }

    private static void style(AttributedStringBuilder b, Token token) {
        b.style(Themes.active().styleOf(token));
    }

    record Viewport(String text, int cursorOffset) {}

    private record Cluster(int start, int end, String text, int width) {}
}
