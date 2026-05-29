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
 * Pure renderer for the first-run provider setup panel (Configure Provider wizard).
 *
 * <p>Produces {@link AttributedString} lines. Does not read keyboard input and has
 * no dependency on the bootstrap or wizard layers.
 *
 * <p>Safe at any width ≥ 1 — every output line is clamped to fit within
 * the requested column count.
 */
public final class ProviderSetupPanel {

    private ProviderSetupPanel() {}

    public static List<AttributedString> render(SetupView view, int width) {
        Objects.requireNonNull(view, "view");
        int w = safeWidth(width);
        List<AttributedString> lines = new ArrayList<>();

        // Header with title
        lines.add(headerLine(view.title(), w));

        // Introduction lines
        for (String introLine : view.introLines()) {
            lines.add(bodyLine(introLine, w));
        }

        // Empty line after intro
        lines.add(emptyLine(w));

        // Field rows with computed label column width
        int maxLabelWidth = 0;
        for (FieldRow row : view.fields()) {
            maxLabelWidth = Math.max(maxLabelWidth, TerminalText.displayWidth(row.label()));
        }
        int labelColumnWidth = maxLabelWidth + 2; // 2 spaces gap

        for (FieldRow row : view.fields()) {
            lines.add(fieldLine(row, labelColumnWidth, w));
        }

        // Error line if present
        if (view.error() != null && !view.error().isBlank()) {
            lines.add(errorLine(view.error(), w));
        }

        // Empty line before footer
        lines.add(emptyLine(w));

        // Footer
        lines.add(footerLine(view.footer(), w));

        return lines;
    }

    // ---- per-line builders -----

    private static AttributedString headerLine(String title, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("╭");
        } else {
            b.append("╭─ ");
            b.style(AttributedStyle.BOLD);
            b.append(title);
            style(b, Token.MUTED);
            int used = 4 + TerminalText.displayWidth(fit(title, Math.max(0, width - 5)));
            int remaining = width - used;
            if (remaining > 0) {
                b.append(" ");
                b.append("─".repeat(remaining));
            }
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString bodyLine(String text, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│ ");
            b.style(AttributedStyle.DEFAULT);
            int budget = Math.max(0, width - 3);
            b.append(fit(text, budget));
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString fieldLine(FieldRow row, int labelColumnWidth, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│ ");
            // Render label padded to labelColumnWidth
            String label = row.label();
            style(b, row.active() ? Token.STATUS_MODE_PLAN : Token.STATUS_KEY);
            String paddedLabel = label + " ".repeat(Math.max(0, labelColumnWidth - TerminalText.displayWidth(label)));
            b.append(paddedLabel);

            // Render value
            b.style(AttributedStyle.DEFAULT);
            int valueBudget = Math.max(0, width - 2 - labelColumnWidth);
            String value = fit(row.value(), valueBudget);
            b.append(value);
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString errorLine(String error, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│ ");
            style(b, Token.TAG_ERROR);
            b.append("Error: ");
            int budget = Math.max(0, width - 9); // "│ Error: " = 9 chars
            b.append(fit(error, budget));
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString emptyLine(int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append("│");
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString footerLine(String footer, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("╰");
        } else {
            b.append("╰─ ");
            int footerWidth = Math.max(0, width - 3);
            b.append(fit(footer, footerWidth));
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    // ---- helpers -------------------------------------------------------

    private static int safeWidth(int width) {
        return Math.max(1, width);
    }

    private static String fit(String value, int columns) {
        return TerminalText.fitEnd(value, Math.max(0, columns));
    }

    private static AttributedString fitLine(AttributedStringBuilder b, int width) {
        String plain = b.toAttributedString().toString();
        int displayWidth = TerminalText.displayWidth(plain);
        if (displayWidth <= width) {
            return b.toAttributedString();
        }
        return new AttributedString(TerminalText.fitEnd(plain, width));
    }

    private static void style(AttributedStringBuilder b, Token token) {
        b.style(Themes.active().styleOf(token));
    }

    // ---- model ---------------------------------------------------------

    public record SetupView(
            String title,
            List<String> introLines,
            List<FieldRow> fields,
            String error,
            String footer) {
        public SetupView {
            title = Objects.requireNonNullElse(title, "");
            introLines = List.copyOf(Objects.requireNonNullElse(introLines, List.of()));
            fields = List.copyOf(Objects.requireNonNullElse(fields, List.of()));
            error = Objects.requireNonNullElse(error, "");
            footer = Objects.requireNonNullElse(footer, "");
        }
    }

    public record FieldRow(String label, String value, boolean active) {
        public FieldRow {
            label = Objects.requireNonNullElse(label, "");
            value = Objects.requireNonNullElse(value, "");
        }
    }
}
