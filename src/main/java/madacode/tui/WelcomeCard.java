package madacode.tui;

import madacode.tui.theme.Tk;
import madacode.tui.theme.Token;

import org.jline.utils.AttributedString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a styled welcome card shown once at startup, before the session
 * picker.
 *
 * <p>Each returned line is a complete scrollback line and may contain ANSI
 * escapes applied via {@link Tk}.
 */
public final class WelcomeCard {

    private static final String VERSION = "v0.1.0";
    private static final String TIPS =
            "/help commands · @file add context · shift+tab cycle mode";
    private static final String WORDMARK_1 =
            "█▀▄▀█ ▄▀█ █▀▄ ▄▀█ █▀▀ █▀█ █▀▄ █▀▀";
    private static final String WORDMARK_2 =
            "█ ▀ █ █▀█ █▄▀ █▀█ █▄▄ █▄█ █▄▀ ██▄";
    private static final int WORDMARK_SPLIT = 17;
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private WelcomeCard() {}

    /**
     * Render a styled welcome card.
     *
     * @param provider      current provider name
     * @param model         current model name
     * @param cwd           working directory
     * @param terminalWidth terminal columns available
     * @return scrollback lines (9 for normal terminals, 3 for very narrow ones)
     */
    public static List<String> render(String provider, String model, Path cwd, int terminalWidth) {
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
        if (terminalWidth < 40) {
            return List.of(
                    "MadaCode " + VERSION,
                    "provider: " + provider,
                    "model:  " + model,
                    "cwd:    " + shortCwd(cwd));
        }

        String rawCwd = shortCwd(cwd);
        if (terminalWidth < 80) {
            return renderCompact(provider, model, rawCwd, terminalWidth);
        }
        return renderWordmark(provider, model, rawCwd, terminalWidth);
    }

    public static List<String> render(String model, Path cwd, int terminalWidth) {
        return render("unknown", model, cwd, terminalWidth);
    }

    // ---- internal helpers -----------------------------------------------

    private static List<String> renderCompact(String provider, String model, String rawCwd, int terminalWidth) {
        int valueWidth = Math.max(1, terminalWidth - 11);
        List<String> lines = new ArrayList<>(6);
        lines.add(accentBold("▌ MadaCode") + Tk.dim(" " + VERSION));
        lines.add(metaRow("model", fitPlain(model, valueWidth), false));
        lines.add(metaRow("provider", fitPlain(provider, valueWidth), false));
        lines.add(metaRow("cwd", fitCwd(rawCwd, valueWidth), true));
        lines.add("");
        lines.add(Tk.dim(TerminalText.fitEnd(TIPS, terminalWidth)));
        return lines;
    }

    private static List<String> renderWordmark(String provider, String model, String rawCwd, int terminalWidth) {
        int cwdWidth = Math.max(12, terminalWidth - 4
                - Tk.displayWidth(VERSION)
                - Tk.displayWidth(model)
                - Tk.displayWidth(provider)
                - 9);
        List<String> lines = new ArrayList<>(6);
        lines.add(wordmarkLine(WORDMARK_1));
        lines.add(wordmarkLine(WORDMARK_2));
        lines.add("");
        lines.add(Tk.dim(VERSION + " · ")
                + model
                + Tk.dim(" · " + provider + " · ")
                + Tk.filePath(fitCwd(rawCwd, cwdWidth)));
        lines.add("");
        lines.add(Tk.dim(TerminalText.fitEnd(TIPS, terminalWidth)));
        return lines;
    }

    private static String wordmarkLine(String line) {
        return Tk.accent(line.substring(0, WORDMARK_SPLIT))
                + " "
                + Tk.toolArg(line.substring(WORDMARK_SPLIT + 1));
    }

    private static String metaRow(String key, String value, boolean path) {
        String label = String.format(java.util.Locale.ROOT, "  %-9s", key);
        return Tk.dim(label) + (path ? Tk.filePath(value) : value);
    }

    private static String accentBold(String value) {
        return new AttributedString(
                value,
                madacode.tui.theme.Themes.active().styleOf(Token.ACCENT).bold()).toAnsi();
    }

    private static String fitPlain(String value, int maxWidth) {
        return TerminalText.fitEnd(value == null ? "" : value, Math.max(1, maxWidth));
    }

    private static String shortCwd(Path cwd) {
        String path = cwd.toAbsolutePath().normalize().toString();
        if (path.startsWith(HOME.toString())) {
            path = "~" + path.substring(HOME.toString().length());
        }
        return path;
    }

    /**
     * Truncate a path for display, keeping the tail and prefixing with
     * {@code ...} when the path is too long.  Display-width safe: measures
     * with {@link Tk#displayWidth} so that ANSI escapes are not counted.
     */
    static String fitCwd(String cwd, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (Tk.displayWidth(cwd) <= maxWidth) return cwd;
        String dots = "...";
        int tailBudget = maxWidth - Tk.displayWidth(dots);
        if (tailBudget <= 0) return dots;
        // walk backwards, collecting full characters until budget exhausted
        StringBuilder tail = new StringBuilder();
        int used = 0;
        for (int i = cwd.length() - 1; i >= 0 && used < tailBudget; i--) {
            char ch = cwd.charAt(i);
            String s = String.valueOf(ch);
            // surrogate pairs — take the whole pair
            if (Character.isLowSurrogate(ch) && i > 0
                    && Character.isHighSurrogate(cwd.charAt(i - 1))) {
                s = cwd.substring(i - 1, i + 1);
                i--;
            }
            int w = Tk.displayWidth(s);
            if (used + w > tailBudget) break;
            tail.insert(0, s);
            used += w;
        }
        return dots + tail;
    }
}
