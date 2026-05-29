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

    private static final AtomicReference<Theme> ACTIVE = new AtomicReference<>(dark());

    private Themes() {}

    public static Theme active() {
        return ACTIVE.get();
    }

    public static void setActive(Theme theme) {
        ACTIVE.set(theme);
    }

    public static List<String> names() {
        return List.of("dark");
    }

    public static boolean setActive(String name) {
        String normalized = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if ("dark".equals(normalized)) {
            setActive(dark());
            return true;
        }
        return false;
    }

    public static Theme dark() {
        return new MapTheme(buildDark());
    }

    private static Map<Token, AttributedStyle> buildDark() {
        EnumMap<Token, AttributedStyle> m = new EnumMap<>(Token.class);
        AttributedStyle d = AttributedStyle.DEFAULT;

        m.put(Token.MUTED, d.faint());
        m.put(Token.EMPHASIS, d.bold());

        m.put(Token.SUCCESS, d.foreground(AttributedStyle.GREEN));
        m.put(Token.FAILURE, d.foreground(AttributedStyle.RED));
        m.put(Token.RUNNING, d.foreground(AttributedStyle.YELLOW));
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

        m.put(Token.PROMPT_ACTIVE, d.bold().foreground(AttributedStyle.WHITE + AttributedStyle.BRIGHT));
        m.put(Token.PROMPT_HISTORY, d.faint());
        return m;
    }

    private record MapTheme(Map<Token, AttributedStyle> map) implements Theme {
        @Override
        public AttributedStyle styleOf(Token token) {
            return map.getOrDefault(token, AttributedStyle.DEFAULT);
        }
    }
}
