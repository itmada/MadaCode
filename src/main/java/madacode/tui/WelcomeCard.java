package madacode.tui;

import madacode.tui.theme.Tk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a styled welcome card shown once at startup, before the session
 * picker.  The card contains an ASCII engine logo on the left and three
 * lines of metadata (welcome / model / cwd) on the right.
 *
 * <p>Box width is content-driven: just wide enough to fit the logo and meta
 * lines, capped by the terminal width.  Each returned line is a complete
 * scrollback line and may contain ANSI escapes applied via {@link Tk}.
 */
public final class WelcomeCard {

    private static final String VERSION = "v0.1.0";
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private static final String[] LOGO = {
            "     ▄▄▄▄▄     ",
            "  ╭──┤███├──╮  ",
            "  │◈◈│MADA│◈◈│ ",
            "  ╰──┤███├──╯  ",
            "     ▀▀▀▀▀     "
    };

    private WelcomeCard() {}

    /**
     * Render a styled welcome card.
     *
     * @param model         current model name
     * @param cwd           working directory
     * @param terminalWidth terminal columns available
     * @return scrollback lines (9 for normal terminals, 3 for very narrow ones)
     */
    public static List<String> render(String model, Path cwd, int terminalWidth) {
        if (terminalWidth < 40) {
            return List.of(
                    "MadaCode " + VERSION,
                    "model:  " + model,
                    "cwd:    " + shortCwd(cwd));
        }

        int logoDisplayWidth = 15;
        int sidePad = 4;  // horizontal padding between border and content
        int gap = 4;      // gap between logo and meta
        int metaStart = sidePad + logoDisplayWidth + gap; // 23

        String user = System.getProperty("user.name", "user");
        String rawCwd = shortCwd(cwd);

        // Measure natural content width from meta strings (displayWidth strips ANSI)
        String[] metaRaw = {
                Tk.dim("Welcome, ") + user,
                Tk.dim("model: ") + model,
                rawCwd
        };
        int naturalMetaWidth = 0;
        for (String s : metaRaw) {
            naturalMetaWidth = Math.max(naturalMetaWidth, Tk.displayWidth(s));
        }

        String title = " MadaCode " + VERSION + " ";
        int innerWidth = Math.max(
                metaStart + naturalMetaWidth + sidePad,  // content + right padding
                Tk.displayWidth(title) + 3);             // title bar minimum
        innerWidth = Math.min(innerWidth, terminalWidth - 4); // don't overflow terminal

        // Build meta with cwd truncated to the actual available space
        int metaMaxWidth = Math.max(0, innerWidth - metaStart - sidePad);
        String[] meta = {
                Tk.dim("Welcome, ") + user,
                Tk.dim("model: ") + model,
                fitCwd(rawCwd, metaMaxWidth)
        };

        List<String> lines = new ArrayList<>(11);

        // ---- top border ----
        int dashPad = Math.max(0, innerWidth - Tk.displayWidth(title));
        lines.add(Tk.bold("╭" + title + "─".repeat(dashPad) + "╮"));

        // ---- 2 blank rows (top vertical padding) ----
        lines.add(Tk.bold("│") + " ".repeat(innerWidth) + Tk.bold("│"));
        lines.add(Tk.bold("│") + " ".repeat(innerWidth) + Tk.bold("│"));

        // ---- logo + meta rows (5 logo lines, meta at rows 1–3) ----
        for (int i = 0; i < LOGO.length; i++) {
            String metaText = (i >= 1 && i <= 3) ? meta[i - 1] : "";
            String line = Tk.bold("│")
                    + " ".repeat(sidePad)
                    + LOGO[i]
                    + " ".repeat(gap)
                    + metaText;
            int visibleLen = Tk.displayWidth(line) - 1; // subtract leading │
            int pad = Math.max(0, innerWidth - visibleLen);
            line = line + " ".repeat(pad) + Tk.bold("│");
            lines.add(line);
        }

        // ---- 2 blank rows (bottom vertical padding) ----
        lines.add(Tk.bold("│") + " ".repeat(innerWidth) + Tk.bold("│"));
        lines.add(Tk.bold("│") + " ".repeat(innerWidth) + Tk.bold("│"));

        // ---- bottom border ----
        lines.add(Tk.bold("╰" + "─".repeat(innerWidth) + "╯"));

        return lines;
    }

    // ---- internal helpers -----------------------------------------------

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
