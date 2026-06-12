package madacode.tui.theme;

import org.jline.utils.AttributedStyle;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Theme factories and the global active-theme holder. The active theme
 * is read on every styled-string render, so swapping themes takes effect
 * immediately for subsequent output.
 */
public final class Themes {

    /** Palette: one indexed color per semantic slot, per theme variant. */
    private record Palette(int success, int failure, int amber, int accent,
                           int path, int link, int code, int thinking, int gray) {}

    private static final Palette DARK = new Palette(71, 167, 179, 173, 110, 75, 180, 139, 243);
    private static final Palette LIGHT = new Palette(28, 124, 136, 130, 25, 26, 94, 96, 245);

    private static final AtomicReference<Theme> ACTIVE = new AtomicReference<>(dark());
    private static volatile boolean basicColorsOnly;
    private static volatile boolean monochrome;

    private Themes() {}

    public static Theme active() {
        return ACTIVE.get();
    }

    public static void setActive(Theme theme) {
        ACTIVE.set(theme);
    }

    public static List<String> names() {
        return List.of("dark", "light");
    }

    public static boolean setActive(String name) {
        String normalized = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if ("dark".equals(normalized)) {
            setActive(dark());
            return true;
        }
        if ("light".equals(normalized)) {
            setActive(light());
            return true;
        }
        return false;
    }

    /** Called once at startup after terminal capability detection. */
    public static void configureCapabilities(boolean basic, boolean mono) {
        basicColorsOnly = basic;
        monochrome = mono;
    }

    public static Theme dark() {
        return themed(DARK, false);
    }

    public static Theme light() {
        return themed(LIGHT, true);
    }

    private static Theme themed(Palette p, boolean lightBackground) {
        if (monochrome) return new MapTheme(buildMono());
        if (basicColorsOnly) return new MapTheme(buildBasic(lightBackground));
        return new MapTheme(build(p, lightBackground));
    }

    private static Map<Token, AttributedStyle> build(Palette p, boolean lightBackground) {
        EnumMap<Token, AttributedStyle> m = new EnumMap<>(Token.class);
        AttributedStyle d = AttributedStyle.DEFAULT;

        m.put(Token.MUTED, d.foreground(p.gray()));
        m.put(Token.EMPHASIS, d.bold());
        m.put(Token.ACCENT, d.foreground(p.accent()));
        m.put(Token.SELECTION, d.inverse().foreground(p.accent()));

        m.put(Token.SUCCESS, d.foreground(p.success()));
        m.put(Token.FAILURE, d.foreground(p.failure()));
        m.put(Token.RUNNING, d.foreground(p.accent()));
        m.put(Token.INFO,    d.foreground(p.gray()));
        m.put(Token.THINKING_PULSE, d.foreground(p.thinking()));

        m.put(Token.TAG_INFO,  d.foreground(p.gray()));
        m.put(Token.TAG_WARN,  d.foreground(p.amber()));
        m.put(Token.TAG_ERROR, d.foreground(p.failure()));

        m.put(Token.TOOL_NAME, d.bold().foreground(p.accent()));
        m.put(Token.TOOL_ARG,  d.foreground(p.code()));
        m.put(Token.FILE_PATH, d.foreground(p.path()));

        m.put(Token.DIFF_ADD,  d.foreground(p.success()));
        m.put(Token.DIFF_DEL,  d.foreground(p.failure()));
        m.put(Token.DIFF_HUNK, d.foreground(p.path()));

        m.put(Token.HEADING,     d.bold());
        m.put(Token.INLINE_CODE, d.foreground(p.code()));
        m.put(Token.CODE_FENCE,  d.foreground(p.gray()));
        m.put(Token.QUOTE,       d.foreground(p.gray()).italic());
        m.put(Token.LINK,        d.foreground(p.link()).underline());

        m.put(Token.STATUS_KEY,       d.foreground(p.gray()));
        m.put(Token.STATUS_VAL,       d);
        m.put(Token.STATUS_MODE_AUTO, d.foreground(p.gray()));
        m.put(Token.STATUS_MODE_PLAN, d.foreground(p.accent()));
        m.put(Token.TIP_AUTO, d.foreground(p.gray()));
        m.put(Token.TIP_PLAN, d.foreground(p.accent()));
        m.put(Token.MODE_INDICATOR_AUTO, d.foreground(p.gray()));
        m.put(Token.MODE_INDICATOR_PLAN, d.foreground(p.accent()));

        m.put(Token.PROMPT_ACTIVE, d.bold().foreground(p.accent()));
        m.put(Token.PROMPT_HISTORY, d.foreground(p.gray()));
        return m;
    }

    private static Map<Token, AttributedStyle> buildBasic(boolean lightBackground) {
        EnumMap<Token, AttributedStyle> m = new EnumMap<>(Token.class);
        AttributedStyle d = AttributedStyle.DEFAULT;

        m.put(Token.MUTED, d.faint());
        m.put(Token.EMPHASIS, d.bold());
        m.put(Token.ACCENT, d.foreground(AttributedStyle.YELLOW));
        m.put(Token.SELECTION, d.inverse());

        m.put(Token.SUCCESS, d.foreground(AttributedStyle.GREEN));
        m.put(Token.FAILURE, d.foreground(AttributedStyle.RED));
        m.put(Token.RUNNING, d.foreground(AttributedStyle.CYAN));
        m.put(Token.INFO,    d.faint());
        m.put(Token.THINKING_PULSE, d.foreground(AttributedStyle.MAGENTA + AttributedStyle.BRIGHT));

        m.put(Token.TAG_INFO,  d.faint());
        m.put(Token.TAG_WARN,  d.foreground(AttributedStyle.YELLOW));
        m.put(Token.TAG_ERROR, d.foreground(AttributedStyle.RED));

        m.put(Token.TOOL_NAME, d.bold().foreground(AttributedStyle.CYAN));
        m.put(Token.TOOL_ARG,  d.foreground(AttributedStyle.YELLOW));
        m.put(Token.FILE_PATH, d.foreground(AttributedStyle.CYAN));

        m.put(Token.DIFF_ADD,  d.foreground(AttributedStyle.GREEN));
        m.put(Token.DIFF_DEL,  d.foreground(AttributedStyle.RED));
        m.put(Token.DIFF_HUNK, d.foreground(AttributedStyle.CYAN));

        m.put(Token.HEADING,     d.bold());
        m.put(Token.INLINE_CODE, d.foreground(AttributedStyle.YELLOW));
        m.put(Token.CODE_FENCE,  d.faint());
        m.put(Token.QUOTE,       d.faint().italic());
        m.put(Token.LINK,        d.foreground(AttributedStyle.CYAN).underline());

        m.put(Token.STATUS_KEY,       d.faint());
        m.put(Token.STATUS_VAL,       d);
        m.put(Token.STATUS_MODE_AUTO, d.faint());
        m.put(Token.STATUS_MODE_PLAN, d.foreground(AttributedStyle.CYAN));
        m.put(Token.TIP_AUTO, d.faint());
        m.put(Token.TIP_PLAN, d.foreground(AttributedStyle.CYAN));
        m.put(Token.MODE_INDICATOR_AUTO, d.faint());
        m.put(Token.MODE_INDICATOR_PLAN, d.foreground(AttributedStyle.CYAN));

        m.put(Token.PROMPT_ACTIVE, lightBackground
                ? d.bold()
                : d.bold().foreground(AttributedStyle.WHITE + AttributedStyle.BRIGHT));
        m.put(Token.PROMPT_HISTORY, d.faint());
        return m;
    }

    private static Map<Token, AttributedStyle> buildMono() {
        EnumMap<Token, AttributedStyle> m = new EnumMap<>(Token.class);
        AttributedStyle d = AttributedStyle.DEFAULT;
        for (Token token : Token.values()) {
            m.put(token, d);
        }

        m.put(Token.MUTED, d.faint());
        m.put(Token.INFO, d.faint());
        m.put(Token.TAG_INFO, d.faint());
        m.put(Token.CODE_FENCE, d.faint());
        m.put(Token.QUOTE, d.faint().italic());
        m.put(Token.STATUS_KEY, d.faint());
        m.put(Token.STATUS_MODE_AUTO, d.faint());
        m.put(Token.TIP_AUTO, d.faint());
        m.put(Token.MODE_INDICATOR_AUTO, d.faint());
        m.put(Token.PROMPT_HISTORY, d.faint());

        m.put(Token.EMPHASIS, d.bold());
        m.put(Token.ACCENT, d.bold());
        m.put(Token.SELECTION, d.inverse());
        m.put(Token.HEADING, d.bold());
        m.put(Token.TOOL_NAME, d.bold());
        m.put(Token.PROMPT_ACTIVE, d.bold());

        m.put(Token.LINK, d.underline());
        return m;
    }

    private record MapTheme(Map<Token, AttributedStyle> map) implements Theme {
        @Override
        public AttributedStyle styleOf(Token token) {
            return map.getOrDefault(token, AttributedStyle.DEFAULT);
        }
    }
}
