package madacode.tui.widget;

import madacode.tui.TerminalText;
import madacode.tui.theme.Themes;
import madacode.tui.theme.Token;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure renderer for the {@code ask_user_question} card.
 *
 * <p>One visual language covers single-select, multi-select and free-text:
 * a divider header with progress, the question, the option rows (each with a
 * state glyph plus an independent focus cursor), a persistent free-text row,
 * and a footer. Selection state ({@code on}) is rendered separately from the
 * focus cursor so the chosen options stay visible while the cursor is on the
 * text row. Produces {@link AttributedString} lines; reads no input.
 */
public final class QuestionCardPanel {

    private static final AttributedStyle DEF = AttributedStyle.DEFAULT;
    private static final int RECOMMENDED_TAG_COLOR = 179; // amber, matches Token.TAG_WARN hue
    private static final int MAX_LABEL_COLUMN = 24;

    private QuestionCardPanel() {}

    public static List<AttributedString> render(View view, int width) {
        Objects.requireNonNull(view, "view");
        int w = Math.max(1, width);
        List<AttributedString> lines = new ArrayList<>();
        lines.add(AttributedString.EMPTY);
        lines.add(headerLine(view.header(), view.progress(), w));
        lines.add(AttributedString.EMPTY);
        if (!view.question().isBlank()) {
            lines.add(plain("  " + view.question(), w));
        }
        lines.add(AttributedString.EMPTY);

        int labelColumn = labelColumn(view.options());
        for (OptionRow option : view.options()) {
            lines.add(optionLine(option, view.multiSelect(), labelColumn, w));
        }

        if (!view.options().isEmpty()) {
            lines.add(AttributedString.EMPTY);
        }
        lines.add(textLine(view, w));
        if (!view.footer().isBlank()) {
            lines.add(AttributedString.EMPTY);
            lines.add(footerLine(view.footer(), w));
        }
        return lines;
    }

    // ---- per-line builders --------------------------------------------------

    private static AttributedString headerLine(String header, String progress, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append("── ");
        b.style(DEF.bold());
        String safeHeader = header == null ? "" : header;
        b.append(safeHeader);
        style(b, Token.MUTED);
        b.append(" ");

        int used = 3 + TerminalText.displayWidth(safeHeader) + 1;
        boolean hasProgress = progress != null && !progress.isBlank();
        int tail = hasProgress ? TerminalText.displayWidth(progress) + 4 : 0; // " nnn ──"
        int dashes = Math.max(1, width - used - tail);
        b.append("─".repeat(dashes));
        if (hasProgress) {
            b.append(" ").append(progress).append(" ──");
        }
        return fit(b, width);
    }

    private static AttributedString optionLine(OptionRow option, boolean multiSelect, int labelColumn, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, option.focus() ? Token.ACCENT : Token.MUTED);
        b.append(option.focus() ? "  ❯ " : "    ");

        String glyph = multiSelect
                ? (option.on() ? "◉ " : "◯ ")
                : (option.on() ? "● " : "○ ");
        style(b, option.on() ? Token.SUCCESS : Token.MUTED);
        b.append(glyph);

        if (option.focus()) {
            b.style(Themes.active().styleOf(Token.ACCENT).bold());
        } else {
            b.style(DEF);
        }
        b.append(padDisplay(option.label(), labelColumn));

        if (!option.description().isBlank()) {
            style(b, Token.MUTED);
            b.append("  ").append(option.description());
        }
        if (option.recommended()) {
            b.style(DEF.foreground(RECOMMENDED_TAG_COLOR));
            b.append("   ★");
        }
        return fit(b, width);
    }

    private static AttributedString textLine(View view, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        boolean focus = view.textFocus();
        style(b, focus ? Token.ACCENT : Token.MUTED);
        b.append(focus ? "  ❯ " : "    ");
        b.append("＋ ");

        String value = view.textValue() == null ? "" : view.textValue();
        if (value.isEmpty() && !focus) {
            style(b, Token.MUTED);
            b.append("Add a note (optional)…");
            return fit(b, width);
        }

        int cursor = Math.max(0, Math.min(view.textCursor(), value.length()));
        b.style(DEF);
        b.append(value.substring(0, cursor));
        if (focus) {
            style(b, Token.SELECTION);
            b.append(cursor < value.length() ? value.substring(cursor, cursor + 1) : " ");
            b.style(DEF);
            if (cursor < value.length()) {
                b.append(value.substring(cursor + 1));
            }
        } else {
            b.append(value.substring(cursor));
        }
        return fit(b, width);
    }

    private static AttributedString footerLine(String footer, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append("  ").append(footer);
        return fit(b, width);
    }

    private static AttributedString plain(String text, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(DEF);
        b.append(text);
        return fit(b, width);
    }

    // ---- helpers ------------------------------------------------------------

    private static int labelColumn(List<OptionRow> options) {
        int max = 0;
        for (OptionRow option : options) {
            max = Math.max(max, TerminalText.displayWidth(option.label()));
        }
        return Math.min(max, MAX_LABEL_COLUMN);
    }

    private static String padDisplay(String value, int columns) {
        String safe = value == null ? "" : value;
        int w = TerminalText.displayWidth(safe);
        return w >= columns ? safe : safe + " ".repeat(columns - w);
    }

    private static void style(AttributedStringBuilder b, Token token) {
        b.style(Themes.active().styleOf(token));
    }

    private static AttributedString fit(AttributedStringBuilder b, int width) {
        AttributedString line = b.toAttributedString();
        if (TerminalText.displayWidth(line.toString()) <= width) {
            return line;
        }
        return new AttributedString(TerminalText.fitEnd(line.toString(), width));
    }

    /** Whether an option's label or description marks it as recommended. */
    public static boolean looksRecommended(String label, String description) {
        String haystack = ((label == null ? "" : label) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        return haystack.contains("recommend") || haystack.contains("推荐");
    }

    // ---- model --------------------------------------------------------------

    public record OptionRow(String label, String description, boolean on, boolean focus, boolean recommended) {
        public OptionRow {
            label = Objects.requireNonNullElse(label, "");
            description = Objects.requireNonNullElse(description, "");
        }
    }

    public record View(
            String header,
            String question,
            String progress,
            boolean multiSelect,
            List<OptionRow> options,
            String textValue,
            int textCursor,
            boolean textFocus,
            String footer) {
        public View {
            header = Objects.requireNonNullElse(header, "");
            question = Objects.requireNonNullElse(question, "");
            progress = Objects.requireNonNullElse(progress, "");
            options = List.copyOf(Objects.requireNonNullElse(options, List.of()));
            textValue = Objects.requireNonNullElse(textValue, "");
            footer = Objects.requireNonNullElse(footer, "");
        }
    }
}
