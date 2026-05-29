package madacode.tui.theme;

import madacode.tui.TerminalText;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * String-styling helpers backed by the active {@link Theme}. All terminal
 * output funnels through these helpers; never emit raw ANSI escape codes
 * from feature code.
 */
public final class Tk {

    private Tk() {}

    /** Wrap {@code s} in the style mapped to {@code token} by the active theme. */
    public static String apply(Token token, String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return new AttributedString(s, Themes.active().styleOf(token)).toAnsi();
    }

    private static String style(AttributedStyle style, String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return new AttributedString(s, style).toAnsi();
    }

    // ---- attribute-only helpers ---------------------------------------
    public static String dim(String s)        { return apply(Token.MUTED, s); }
    public static String bold(String s)       { return apply(Token.EMPHASIS, s); }
    public static String italic(String s)     { return style(AttributedStyle.DEFAULT.italic(), s); }

    // ---- semantic helpers ---------------------------------------------
    public static String success(String s)    { return apply(Token.SUCCESS, s); }
    public static String failure(String s)    { return apply(Token.FAILURE, s); }
    public static String running(String s)    { return apply(Token.RUNNING, s); }
    public static String thinking(String s)   { return apply(Token.THINKING_PULSE, s); }
    public static String info(String s)       { return apply(Token.INFO, s); }
    public static String toolName(String s)   { return apply(Token.TOOL_NAME, s); }
    public static String toolArg(String s)    { return apply(Token.TOOL_ARG, s); }
    public static String filePath(String s)   { return apply(Token.FILE_PATH, s); }
    public static String diffAdd(String s)    { return apply(Token.DIFF_ADD, s); }
    public static String diffDel(String s)    { return apply(Token.DIFF_DEL, s); }
    public static String diffHunk(String s)   { return apply(Token.DIFF_HUNK, s); }
    public static String heading(String s)    { return apply(Token.HEADING, s); }
    public static String inlineCode(String s) { return apply(Token.INLINE_CODE, s); }
    public static String codeFence(String s)  { return apply(Token.CODE_FENCE, s); }
    public static String quote(String s)      { return apply(Token.QUOTE, s); }
    public static String link(String s)       { return apply(Token.LINK, s); }

    public static String infoTag(String tag)  { return apply(Token.TAG_INFO,  "[" + tag + "]"); }
    public static String warnTag(String tag)  { return apply(Token.TAG_WARN,  "[" + tag + "]"); }
    public static String errorTag(String tag) { return apply(Token.TAG_ERROR, "[" + tag + "]"); }

    public static String promptActive(String s) { return apply(Token.PROMPT_ACTIVE, s); }
    public static String promptHistory(String s) { return apply(Token.PROMPT_HISTORY, s); }

    /**
     * Display width of a string in terminal columns: CJK / wide code points
     * count as 2, control / zero-width as 0. Use this whenever a layout
     * needs to fit within a column budget.
     */
    public static int displayWidth(String s) {
        return TerminalText.displayWidth(s);
    }

    /**
     * Format a token count with one decimal of precision for human display.
     * < 1000 → raw; 1k–10k → x.xk; 10k–1M → xk; ≥ 1M → x.xm.
     */
    public static String formatCount(int value) {
        int safe = Math.max(0, value);
        if (safe >= 1_000_000) {
            double v = safe / 1_000_000.0;
            String s = String.format("%.1f", v);
            return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + "m";
        }
        if (safe >= 10_000) {
            return (safe / 1_000) + "k";
        }
        if (safe >= 1_000) {
            double v = safe / 1_000.0;
            String s = String.format("%.1f", v);
            return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + "k";
        }
        return Integer.toString(safe);
    }
}
