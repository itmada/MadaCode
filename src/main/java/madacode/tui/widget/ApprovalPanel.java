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

import static madacode.tui.theme.Tk.*;

/**
 * Pure renderer for a permission-approval panel with vertical selection list.
 *
 * <p>Produces {@link AttributedString} lines for use with {@link madacode.tui.Screen#setLiveModal}.
 * Does not read keyboard input and has no dependency on the CLI or permission layers.
 *
 * <p>Safe at any width ≥ 1 — every output line is clamped to fit within
 * the requested column count.
 */
public final class ApprovalPanel {

    private static final int MAX_DETAIL_COLUMNS = 84;

    private ApprovalPanel() {}

    public static List<AttributedString> render(ApprovalRequestView view, int width) {
        Objects.requireNonNull(view, "view");
        int safeWidth = safeWidth(width);
        List<AttributedString> lines = new ArrayList<>();

        lines.add(headerLine(view.title(), safeWidth));
        if (!view.subject().isBlank()) {
            lines.add(bodyLine(view.subject(), Token.TOOL_NAME, safeWidth));
        }
        if (!view.detail().isBlank()) {
            lines.add(bodyLine(view.detail(), Token.FILE_PATH, safeWidth));
        }
        for (String previewLine : view.previewLines()) {
            lines.add(bodyLine(previewLine, Token.MUTED, safeWidth));
        }
        lines.add(emptyLine(safeWidth));
        for (int i = 0; i < view.actions().size(); i++) {
            Action action = view.actions().get(i);
            boolean selected = i == view.selectedIndex();
            lines.add(actionLine(action, selected, safeWidth));
        }
        lines.add(footerLine(view.footer(), safeWidth));

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
            style(b, Token.STATUS_KEY);
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

    private static AttributedString bodyLine(String text, Token textToken, int width) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        style(b, Token.MUTED);
        if (width <= 2) {
            b.append("│");
        } else {
            b.append("│ ");
            if (width >= 5) {
                b.append(" ");
                style(b, textToken);
                String truncated = TerminalText.truncateMiddle(
                        text.replace('\n', ' ').replace('\r', ' ').strip(), MAX_DETAIL_COLUMNS);
                int contentWidth = Math.max(0, width - 4);
                b.append(fit(truncated, contentWidth));
            }
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

    private static AttributedString actionLine(Action action, boolean selected, int width) {
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
                style(b, action.destructive() ? Token.TAG_WARN : Token.STATUS_VAL);
                int labelWidth = Math.max(0, width - 5);
                String hotkey = action.hotkey().isBlank() ? "" : "[" + action.hotkey() + "] ";
                b.append(fit(hotkey + action.label(), labelWidth));
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

    /**
     * Ensures the display width of the rendered line does not exceed {@code width}.
     * Truncates with fitEnd when needed; the style at the cut point is lost but
     * this only happens on extremely narrow terminals (1–3 cols) where the
     * visual degradation is acceptable.
     */
    private static AttributedString fitLine(AttributedStringBuilder b, int width) {
        String plain = b.toAttributedString().toString();
        int displayWidth = TerminalText.displayWidth(plain);
        if (displayWidth <= width) {
            return b.toAttributedString();
        }
        // Truncate the plain-text representation to fit.
        return new AttributedString(TerminalText.fitEnd(plain, width));
    }

    private static void style(AttributedStringBuilder b, Token token) {
        b.style(Themes.active().styleOf(token));
    }

    // ---- Inline rendering for ToolCardRenderable --------------------------

    private static final List<Action> INLINE_ACTIONS = List.of(
            new Action(Decision.ALLOW_ONCE, "Allow once", false, "a"),
            new Action(Decision.ALLOW_SESSION, "Allow for session", false, "s"),
            new Action(Decision.DENY, "Deny", true, "d"));

    private static final String INLINE_FOOTER = "↑↓ select   Enter confirm   Esc deny";

    /**
     * Produces ANSI-styled lines for inline permission approval, suitable for
     * embedding inside a {@link ToolCardRenderable} render output.
     */
    public static List<String> renderInlineApproval(int width, int selectedIdx) {
        int safeWidth = Math.max(1, width);
        int idx = Math.max(0, Math.min(selectedIdx, INLINE_ACTIONS.size() - 1));
        List<String> lines = new ArrayList<>();

        // Header
        StringBuilder hdr = new StringBuilder();
        hdr.append("╭─ Permission required");
        int hdrContentLen = hdr.length();
        if (safeWidth > hdrContentLen + 3) {
            hdr.append(" ").append("─".repeat(safeWidth - hdrContentLen - 2));
        }
        lines.add(dim(TerminalText.fitEnd(hdr.toString(), safeWidth)));

        // Action lines
        for (int i = 0; i < INLINE_ACTIONS.size(); i++) {
            Action action = INLINE_ACTIONS.get(i);
            boolean selected = i == idx;
            String prefix = selected ? "> " : "  ";
            String hotkey = "[" + action.hotkey() + "] ";
            String label = dim("│") + " " + prefix + hotkey + action.label();
            lines.add(TerminalText.fitEnd(label, safeWidth));
        }

        // Footer
        StringBuilder ftr = new StringBuilder();
        ftr.append("╰─ ").append(INLINE_FOOTER);
        lines.add(dim(TerminalText.fitEnd(ftr.toString(), safeWidth)));

        return lines;
    }

    // ---- model types ---------------------------------------------------

    public enum Decision {
        DENY,
        ALLOW_ONCE,
        ALLOW_SESSION
    }

    public record Action(Decision decision, String label, boolean destructive, String hotkey) {
        public Action(Decision decision, String label, boolean destructive) {
            this(decision, label, destructive, "");
        }

        public Action {
            Objects.requireNonNull(decision, "decision");
            label = Objects.requireNonNullElse(label, "");
            hotkey = Objects.requireNonNullElse(hotkey, "");
        }
    }

    public record ApprovalRequestView(
            String title,
            String subject,
            String detail,
            List<String> previewLines,
            List<Action> actions,
            int selectedIndex,
            String footer) {
        public ApprovalRequestView(
                String title,
                String subject,
                String detail,
                List<Action> actions,
                int selectedIndex,
                String footer) {
            this(title, subject, detail, List.of(), actions, selectedIndex, footer);
        }

        public ApprovalRequestView {
            title = Objects.requireNonNullElse(title, "");
            subject = Objects.requireNonNullElse(subject, "");
            detail = Objects.requireNonNullElse(detail, "");
            previewLines = List.copyOf(Objects.requireNonNullElse(previewLines, List.of()));
            actions = List.copyOf(Objects.requireNonNullElse(actions, List.of()));
            selectedIndex = actions.isEmpty()
                    ? 0
                    : Math.max(0, Math.min(selectedIndex, actions.size() - 1));
            footer = Objects.requireNonNullElse(footer, "");
        }
    }
}
