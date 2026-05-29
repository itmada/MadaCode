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
            String footer) {

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

        // Header
        lines.add(headerLine(view.title(), w));

        // Input line with cursor
        lines.add(inputLine(view.input(), view.cursor(), w));

        // Separator
        lines.add(separatorLine(w));

        // Candidate list
        for (int i = 0; i < view.candidates().size(); i++) {
            PaletteCandidate c = view.candidates().get(i);
            boolean sel = i == view.selected();
            lines.add(candidateLine(c.primary(), c.secondary(), sel, w));
        }

        // Footer
        if (!view.footer().isBlank()) {
            lines.add(separatorLine(w));
            lines.add(footerLine(view.footer(), w));
        }

        return lines;
    }

    // ---- per-line builders ---------------------------------------------

    private static AttributedString headerLine(String title, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("╭");
        } else {
            b.append("╭─ ");
            b.style(AttributedStyle.BOLD);
            b.append(fit(title, Math.max(0, width - 5)));
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

    private static AttributedString inputLine(String input, int cursor, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else if (width < 5) {
            b.append("│");
        } else {
            b.append("│  ");
            b.style(AttributedStyle.DEFAULT);

            int budget = width - 4; // after "│  "
            String visible = fitEnd(input, budget);

            // Build visible portion showing cursor as inverse block
            for (int i = 0; i < visible.length(); i++) {
                if (i == cursor) {
                    style(b, Token.STATUS_MODE_PLAN);
                }
                b.append(visible.charAt(i));
            }
            // Cursor at end of visible string
            if (cursor >= visible.length() && cursor <= input.length()) {
                style(b, Token.STATUS_MODE_PLAN);
                if (cursor == input.length()) {
                    b.append(' '); // cursor block at end
                }
            }
            b.style(AttributedStyle.DEFAULT);
        }
        return fitLine(b, width);
    }

    private static AttributedString separatorLine(int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append(width <= 2 ? "│" : "│");
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString candidateLine(String primary, String secondary,
                                                   boolean selected, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│ ");
            if (width >= 5) {
                b.append(" ");
                if (selected) {
                    style(b, Token.STATUS_MODE_PLAN);
                    b.append("> ");
                } else {
                    b.append("  ");
                }
                int budget = width - 5;
                style(b, selected ? Token.STATUS_MODE_PLAN : Token.MUTED);
                b.append(fitEnd(primary, budget));

                if (!secondary.isBlank()) {
                    int used = TerminalText.displayWidth(primary);
                    int secBudget = budget - used - 2;
                    if (secBudget > 4) {
                        b.append("  ");
                        style(b, Token.MUTED);
                        b.append(fitEnd(secondary, secBudget));
                    }
                }
            }
        }
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
            b.append(fit(footer, Math.max(0, width - 4)));
        }
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
}
