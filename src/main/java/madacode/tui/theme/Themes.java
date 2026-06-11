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

    // 256-color (xterm indexed) palette. Mid-tone indices chosen to stay
    // readable on both dark and light terminal backgrounds.
    private static final int GREEN_SOFT = 71;   // success / diff add
    private static final int RED_SOFT   = 167;  // failure / diff del
    private static final int AMBER      = 179;  // running / warnings
    private static final int TEAL       = 80;   // brand accent: tool names, plan mode
    private static final int STEEL_BLUE = 110;  // file paths / diff hunks
    private static final int SKY_BLUE   = 75;   // links
    private static final int SAND       = 180;  // inline code / tool args
    private static final int ORCHID     = 177;  // thinking pulse

    private static Map<Token, AttributedStyle> buildDark() {
        EnumMap<Token, AttributedStyle> m = new EnumMap<>(Token.class);
        AttributedStyle d = AttributedStyle.DEFAULT;

        m.put(Token.MUTED, d.faint());
        m.put(Token.EMPHASIS, d.bold());

        m.put(Token.SUCCESS, d.foreground(GREEN_SOFT));
        m.put(Token.FAILURE, d.foreground(RED_SOFT));
        m.put(Token.RUNNING, d.foreground(AMBER));
        m.put(Token.INFO,    d.faint());
        m.put(Token.THINKING_PULSE, d.foreground(ORCHID));

        m.put(Token.TAG_INFO,  d.faint());
        m.put(Token.TAG_WARN,  d.foreground(AMBER));
        m.put(Token.TAG_ERROR, d.foreground(RED_SOFT));

        m.put(Token.TOOL_NAME, d.bold().foreground(TEAL));
        m.put(Token.TOOL_ARG,  d.foreground(SAND));
        m.put(Token.FILE_PATH, d.foreground(STEEL_BLUE));

        m.put(Token.DIFF_ADD,  d.foreground(GREEN_SOFT));
        m.put(Token.DIFF_DEL,  d.foreground(RED_SOFT));
        m.put(Token.DIFF_HUNK, d.foreground(STEEL_BLUE));

        m.put(Token.HEADING,     d.bold());
        m.put(Token.INLINE_CODE, d.foreground(SAND));
        m.put(Token.CODE_FENCE,  d.faint());
        m.put(Token.QUOTE,       d.faint().italic());
        m.put(Token.LINK,        d.foreground(SKY_BLUE).underline());

        m.put(Token.STATUS_KEY,       d.faint());
        m.put(Token.STATUS_VAL,       d);
        m.put(Token.STATUS_MODE_AUTO, d.faint());
        m.put(Token.STATUS_MODE_PLAN, d.foreground(TEAL));
        m.put(Token.TIP_AUTO, d.faint());
        m.put(Token.TIP_PLAN, d.foreground(TEAL));
        m.put(Token.MODE_INDICATOR_AUTO, d.faint());
        m.put(Token.MODE_INDICATOR_PLAN, d.foreground(TEAL));

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
