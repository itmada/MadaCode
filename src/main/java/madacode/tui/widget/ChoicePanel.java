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
 * Pure renderer for a choice selection panel (model, theme, session resume, etc.).
 *
 * <p>Produces {@link AttributedString} lines. Does not read keyboard input.
 */
public final class ChoicePanel {

    private ChoicePanel() {}

    public static List<AttributedString> render(ChoiceView view, int width) {
        Objects.requireNonNull(view, "view");
        int w = safeWidth(width);
        List<AttributedString> lines = new ArrayList<>();
        lines.add(AttributedString.EMPTY);

        // header
        if (!view.title().isBlank()) {
            lines.add(headerLine(view.title(), w));
        }
        // subtitle
        if (!view.subtitle().isBlank()) {
            lines.add(subtitleLine(view.subtitle(), w));
        }
        // spacing
        if (!view.title().isBlank() || !view.subtitle().isBlank()) {
            lines.add(separatorLine(w));
        }

        if (view.horizontal()) {
            lines.add(horizontalOptionsLine(view.options(), view.selectedIndex(), w));
        } else {
            for (int i = 0; i < view.options().size(); i++) {
                ChoiceOption option = view.options().get(i);
                boolean selected = i == view.selectedIndex();
                lines.add(optionLine(option, selected, w));
            }
        }

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

    private static AttributedString subtitleLine(String subtitle, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│  ");
            b.style(AttributedStyle.DEFAULT);
            int budget = width - 4;
            b.append(fit(subtitle, Math.max(0, budget)));
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString separatorLine(int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│");
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString optionLine(ChoiceOption option, boolean selected, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        if (width <= 2) {
            style(b, Token.MUTED);
            b.append("│");
        } else {
            style(b, Token.MUTED);
            b.append("│ ");
            if (width >= 5) {
                b.append(" ");
                if (selected) {
                    style(b, Token.STATUS_MODE_PLAN);
                    b.append("> ");
                } else {
                    b.append("  ");
                }
                int budget = Math.max(1, width - 5);
                String hotkey = option.hotkey().isBlank() ? "" : "[" + option.hotkey() + "] ";
                String primary = fit(hotkey + option.primary(), budget);
                style(b, selected ? Token.STATUS_MODE_PLAN : Token.MUTED);
                b.append(primary);
                if (!option.secondary().isBlank() && budget > TerminalText.displayWidth(primary) + 3) {
                    b.append("  ");
                    int secBudget = budget - TerminalText.displayWidth(primary) - 2;
                    if (secBudget > 4) {
                        style(b, Token.MUTED);
                        b.append(fit(option.secondary(), secBudget));
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

    private static AttributedString horizontalOptionsLine(
            List<ChoiceOption> options, int selected, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 4) {
            b.append("│");
        } else {
            b.append("│  ");
            for (int i = 0; i < options.size(); i++) {
                ChoiceOption opt = options.get(i);
                boolean sel = i == selected;
                String hotkey = opt.hotkey().isBlank() ? "" : "[" + opt.hotkey() + "] ";
                String label = hotkey + opt.primary();
                if (sel) {
                    style(b, Token.STATUS_MODE_PLAN);
                    b.append("▸ ");
                } else {
                    style(b, Token.MUTED);
                    b.append("  ");
                }
                style(b, sel ? Token.STATUS_MODE_PLAN : Token.MUTED);
                b.append(label);
                if (i < options.size() - 1) {
                    style(b, Token.MUTED);
                    b.append("   ");
                }
            }
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

    public record ChoiceView(
            String title,
            String subtitle,
            List<ChoiceOption> options,
            int selectedIndex,
            String footer,
            boolean horizontal) {
        public ChoiceView(String title, String subtitle, List<ChoiceOption> options, int selectedIndex, String footer) {
            this(title, subtitle, options, selectedIndex, footer, false);
        }

        public ChoiceView {
            title = Objects.requireNonNullElse(title, "");
            subtitle = Objects.requireNonNullElse(subtitle, "");
            options = List.copyOf(Objects.requireNonNullElse(options, List.of()));
            selectedIndex = options.isEmpty()
                    ? 0 : Math.max(0, Math.min(selectedIndex, options.size() - 1));
            footer = Objects.requireNonNullElse(footer, "");
        }
    }

    public record ChoiceOption(String primary, String secondary, String meta, String hotkey) {
        public ChoiceOption(String primary, String secondary, String meta) {
            this(primary, secondary, meta, "");
        }

        public ChoiceOption {
            primary = Objects.requireNonNullElse(primary, "");
            secondary = Objects.requireNonNullElse(secondary, "");
            meta = Objects.requireNonNullElse(meta, "");
            hotkey = Objects.requireNonNullElse(hotkey, "");
        }
    }
}
