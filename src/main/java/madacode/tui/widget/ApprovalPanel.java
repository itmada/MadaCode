package madacode.tui.widget;

import madacode.tui.TerminalText;
import madacode.tui.theme.Themes;
import madacode.tui.theme.Token;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static madacode.tui.theme.Tk.*;

/**
 * Pure renderer for a permission-approval panel with horizontal actions.
 *
 * <p>Produces {@link AttributedString} lines for use with {@link madacode.tui.Screen#setLiveModal}.
 * Does not read keyboard input and has no dependency on the CLI or permission layers.
 *
 * <p>Safe at any width ≥ 1 — every output line is clamped to fit within
 * the requested column count.
 */
public final class ApprovalPanel {

    private static final int MAX_DETAIL_COLUMNS = 84;
    private static final String INLINE_TITLE = "Permission required";
    private static final String FOOTER = "←/→ select · enter confirm · esc deny";
    private static final List<Action> DEFAULT_ACTIONS = List.of(
            new Action(Decision.ALLOW_ONCE, "allow once", false, "y"),
            new Action(Decision.ALLOW_SESSION, "allow session", false, "s"),
            new Action(Decision.DENY, "deny", true, "n"));

    private ApprovalPanel() {}

    public static List<Action> defaultActions() {
        return DEFAULT_ACTIONS;
    }

    public static ApprovalRequestView modalView(String subject, String detail, int selectedIndex) {
        return new ApprovalRequestView(
                INLINE_TITLE,
                subject,
                detail,
                DEFAULT_ACTIONS,
                selectedIndex,
                FOOTER);
    }

    public static List<AttributedString> render(ApprovalRequestView view, int width) {
        Objects.requireNonNull(view, "view");
        List<String> ansi = renderPanel(
                width,
                view.selectedIndex(),
                view.subject(),
                view.detail(),
                view.previewLines(),
                view.actions(),
                view.footer());
        List<AttributedString> lines = new ArrayList<>(ansi.size());
        for (String line : ansi) {
            lines.add(AttributedString.fromAnsi(line));
        }
        return lines;
    }

    // ---- helpers -------------------------------------------------------

    private static int safeWidth(int width) {
        return Math.max(1, width);
    }

    private static String sanitize(String value) {
        return TerminalText.truncateMiddle(
                value.replace('\n', ' ').replace('\r', ' ').strip(), MAX_DETAIL_COLUMNS);
    }

    // ---- Inline rendering for ToolCardRenderable --------------------------

    /**
     * Produces ANSI-styled lines for inline permission approval, suitable for
     * embedding inside a {@link ToolCardRenderable} render output.
     */
    public static List<String> renderInlineApproval(int width, int selectedIdx) {
        return renderInlineApproval(width, selectedIdx, "", "");
    }

    public static List<String> renderInlineApproval(
            int width, int selectedIdx, String subject, String detail) {
        return renderPanel(width, selectedIdx, subject, detail, List.of(), DEFAULT_ACTIONS, FOOTER);
    }

    private static List<String> renderPanel(
            int width,
            int selectedIdx,
            String subject,
            String detail,
            List<String> previewLines,
            List<Action> actions,
            String footer) {
        int safeWidth = safeWidth(width);
        int idx = actions.isEmpty() ? 0 : Math.max(0, Math.min(selectedIdx, actions.size() - 1));
        SubjectParts parts = subjectParts(subject, detail);
        List<String> lines = new ArrayList<>();
        lines.add(topLine(safeWidth));
        lines.add(contentLine(toolSubject(parts), safeWidth));
        if (!parts.contextLine().isBlank()) {
            lines.add(contentLine(dim("in ") + filePath(parts.contextLine()), safeWidth));
        }
        for (String previewLine : previewLines) {
            lines.add(contentLine(dim(sanitize(previewLine)), safeWidth));
        }
        lines.add(contentLine("", safeWidth));
        if (!actions.isEmpty()) {
            lines.add(contentLine(actionsLine(actions, idx), safeWidth));
        }
        lines.add(bottomLine(footer, safeWidth));
        return lines;
    }

    private static String topLine(int width) {
        if (width <= 1) {
            return dim(TerminalText.fitEnd("╭", width));
        }
        String prefix = dim("╭─ ") + apply(Token.TAG_WARN, "▲") + " " + bold(INLINE_TITLE) + dim(" ");
        return fillWithRule(prefix, width);
    }

    private static String bottomLine(String footer, int width) {
        if (width <= 1) {
            return dim(TerminalText.fitEnd("╰", width));
        }
        String prefix = dim("╰─ " + (footer == null || footer.isBlank() ? FOOTER : footer) + " ");
        return fillWithRule(prefix, width);
    }

    private static String fillWithRule(String prefix, int width) {
        int remaining = width - TerminalText.displayWidth(prefix);
        if (remaining <= 0) {
            return TerminalText.fitEnd(prefix, width);
        }
        return prefix + dim("─".repeat(remaining));
    }

    private static String contentLine(String body, int width) {
        String line = dim("│");
        if (width > 1) {
            line += " " + body;
        }
        return padOrFit(line, width);
    }

    private static String actionsLine(List<Action> actions, int selectedIdx) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            boolean selected = i == selectedIdx;
            String label = action.label();
            if (selected) {
                String pill = " ❯ " + label + " ";
                line.append(action.destructive() ? selectionDanger(pill) : selection(pill));
            } else {
                line.append(dim(label));
            }
            if (i < actions.size() - 1) {
                line.append("    ");
            }
        }
        return " " + line;
    }

    private static String toolSubject(SubjectParts parts) {
        String arg = parts.arg();
        return toolName(parts.label()) + (arg.isBlank() ? "" : " " + toolArg(arg));
    }

    private static SubjectParts subjectParts(String subject, String detail) {
        String cleanSubject = sanitize(Objects.requireNonNullElse(subject, ""));
        String cleanDetail = sanitize(Objects.requireNonNullElse(detail, ""));
        String label = cleanSubject.isBlank() ? "tool" : cleanSubject;
        String arg = "";
        int open = cleanSubject.indexOf('(');
        if (open > 0 && cleanSubject.endsWith(")")) {
            label = cleanSubject.substring(0, open).strip();
            arg = cleanSubject.substring(open + 1, cleanSubject.length() - 1).strip();
        } else if (!cleanDetail.isBlank() && !looksPath(cleanDetail)) {
            arg = cleanDetail;
        }
        String context = looksPath(cleanDetail) ? cleanDetail : "";
        return new SubjectParts(normalizeLabel(label), arg, context);
    }

    private static boolean looksPath(String value) {
        String clean = value == null ? "" : value.strip();
        return clean.startsWith("/")
                || clean.startsWith("~/")
                || clean.startsWith("../")
                || clean.startsWith("./");
    }

    private static String normalizeLabel(String value) {
        String clean = Objects.requireNonNullElse(value, "").strip();
        return switch (clean) {
            case "Read" -> "file_read";
            case "Write" -> "file_write";
            case "Edit" -> "file_edit";
            case "Bash" -> "bash";
            case "Grep", "Search" -> "grep";
            case "Glob", "List" -> "glob";
            case "Fetch", "WebFetch" -> "web_fetch";
            case "Skill" -> "skill";
            case "Agent" -> "agent";
            default -> clean.isBlank() ? "tool" : snakeCase(clean);
        };
    }

    private static String snakeCase(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isUpperCase(ch) && !out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.isEmpty() ? "tool" : out.toString();
    }

    private static String selectionDanger(String value) {
        AttributedStyle style = Themes.active().styleOf(Token.FAILURE).inverse();
        return new AttributedString(value, style).toAnsi();
    }

    private static String padOrFit(String line, int width) {
        if (TerminalText.displayWidth(line) > width) {
            return TerminalText.fitEnd(line, width);
        }
        return line + " ".repeat(Math.max(0, width - TerminalText.displayWidth(line)));
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

    private record SubjectParts(String label, String arg, String contextLine) {}

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
