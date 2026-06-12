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

        if (!view.title().isBlank()) {
            lines.add(dividerLine(view.title(), w));
        }
        if (!view.subtitle().isBlank()) {
            lines.add(subtitleLine(view.subtitle(), w));
        }
        if (!view.filter().isBlank() || view.noMatch()) {
            lines.add(filterLine(view.filter(), view.noMatch(), w));
        }

        if (view.horizontal()) {
            lines.add(horizontalOptionsLine(view.options(), view.selectedIndex(), view.filter(), w));
        } else {
            for (int i = 0; i < view.options().size(); i++) {
                ChoiceOption option = view.options().get(i);
                boolean selected = i == view.selectedIndex();
                lines.add(optionLine(option, selected, view.filter(), w));
            }
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
        int titleBudget = Math.max(0, width - TerminalText.displayWidth(prefix) - 1);
        String visibleTitle = fit(title, titleBudget);
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

    private static AttributedString subtitleLine(String subtitle, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append(fit(subtitle, width));
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString optionLine(ChoiceOption option, boolean selected, String filter, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        String prefix = selected ? "❯ " : "  ";
        style(b, selected ? Token.ACCENT : Token.MUTED);
        b.append(prefix);
        int budget = Math.max(0, width - TerminalText.displayWidth(prefix));
        String hotkey = option.hotkey().isBlank() ? "" : "[" + option.hotkey() + "] ";
        appendPrimary(b, hotkey + option.primary(), filter, selected, budget);
        appendOptionDetail(b, option, budget,
                TerminalText.displayWidth(hotkey + option.primary()));
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

    private static AttributedString horizontalOptionsLine(
            List<ChoiceOption> options, int selected, String filter, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        for (int i = 0; i < options.size(); i++) {
            ChoiceOption opt = options.get(i);
            boolean sel = i == selected;
            String hotkey = opt.hotkey().isBlank() ? "" : "[" + opt.hotkey() + "] ";
            if (i > 0) {
                style(b, Token.MUTED);
                b.append("   ");
            }
            style(b, sel ? Token.ACCENT : Token.MUTED);
            b.append(sel ? "❯ " : "  ");
            appendPrimary(b, hotkey + opt.primary(), filter, sel, Integer.MAX_VALUE);
            int used = TerminalText.displayWidth(b.toAttributedString().toString());
            appendOptionDetail(b, opt, Math.max(0, width - used),
                    0);
        }
        b.style(AttributedStyle.DEFAULT);
        return fitLine(b, width);
    }

    private static AttributedString filterLine(String filter, boolean noMatch, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        b.append("filter: ");
        b.append(fit(filter, Math.max(0, width - 10)));
        style(b, Token.SELECTION);
        b.append(" ");
        if (noMatch) {
            style(b, Token.MUTED);
            b.append("  (no match)");
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

    private static void appendOptionDetail(
            AttributedStringBuilder b,
            ChoiceOption option,
            int lineBudget,
            int usedAfterPrefix) {
        String detail = option.meta().isBlank() ? option.secondary() : option.meta();
        if ("current".equalsIgnoreCase(detail)) {
            detail = "✓ current";
        }
        if (detail.isBlank()) {
            return;
        }
        int remaining = lineBudget - usedAfterPrefix - TerminalText.displayWidth("   ");
        if (remaining <= 0) {
            return;
        }
        style(b, Token.MUTED);
        b.append("   ");
        b.append(fit(detail, remaining));
    }

    private static void style(AttributedStringBuilder b, Token token) {
        b.style(Themes.active().styleOf(token));
    }

    private static void appendPrimary(
            AttributedStringBuilder b,
            String primary,
            String filter,
            boolean selected,
            int budget) {
        String visible = fit(primary, budget);
        if (selected) {
            style(b, Token.ACCENT);
            b.style(Themes.active().styleOf(Token.ACCENT).bold());
            b.append(visible);
            return;
        }
        int match = matchIndex(visible, filter);
        if (match < 0) {
            b.style(AttributedStyle.DEFAULT);
            b.append(visible);
            return;
        }
        b.style(AttributedStyle.DEFAULT);
        b.append(visible.substring(0, match));
        style(b, Token.ACCENT);
        int end = Math.min(visible.length(), match + filter.length());
        b.append(visible.substring(match, end));
        b.style(AttributedStyle.DEFAULT);
        b.append(visible.substring(end));
    }

    private static int matchIndex(String value, String filter) {
        if (value == null || filter == null || filter.isBlank()) {
            return -1;
        }
        return value.toLowerCase(java.util.Locale.ROOT)
                .indexOf(filter.toLowerCase(java.util.Locale.ROOT));
    }

    // ---- model ---------------------------------------------------------

    public record ChoiceView(
            String title,
            String subtitle,
            List<ChoiceOption> options,
            int selectedIndex,
            String footer,
            boolean horizontal,
            String filter,
            boolean noMatch) {
        public ChoiceView(String title, String subtitle, List<ChoiceOption> options, int selectedIndex, String footer) {
            this(title, subtitle, options, selectedIndex, footer, false);
        }

        public ChoiceView(
                String title,
                String subtitle,
                List<ChoiceOption> options,
                int selectedIndex,
                String footer,
                boolean horizontal) {
            this(title, subtitle, options, selectedIndex, footer, horizontal, "", false);
        }

        public ChoiceView {
            title = Objects.requireNonNullElse(title, "");
            subtitle = Objects.requireNonNullElse(subtitle, "");
            options = List.copyOf(Objects.requireNonNullElse(options, List.of()));
            selectedIndex = options.isEmpty()
                    ? 0 : Math.max(0, Math.min(selectedIndex, options.size() - 1));
            footer = Objects.requireNonNullElse(footer, "");
            filter = Objects.requireNonNullElse(filter, "");
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
