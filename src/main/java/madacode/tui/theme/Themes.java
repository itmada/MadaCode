package madacode.tui.theme;

import org.jline.utils.AttributedStyle;

import java.util.EnumMap;
import java.util.Map;

/**
 * Theme access point for the TUI. For now the runtime has a single default
 * theme; the {@link Theme} interface remains the extension point for a future
 * fuller theme system.
 */
public final class Themes {

    private static final Theme DEFAULT = new MapTheme(defaultStyles());

    private Themes() {}

    public static Theme active() {
        return DEFAULT;
    }

    private static Map<Token, AttributedStyle> defaultStyles() {
        EnumMap<Token, AttributedStyle> m = new EnumMap<>(Token.class);
        AttributedStyle d = AttributedStyle.DEFAULT;

        int success = 71;
        int failure = 167;
        int amber = 179;
        int accent = 173;
        int path = 110;
        int link = 75;
        int code = 180;
        int thinking = 139;
        int gray = 243;

        m.put(Token.MUTED, d.foreground(gray));
        m.put(Token.EMPHASIS, d.bold());
        m.put(Token.ACCENT, d.foreground(accent));
        m.put(Token.SELECTION, d.inverse().foreground(accent));

        m.put(Token.SUCCESS, d.foreground(success));
        m.put(Token.FAILURE, d.foreground(failure));
        m.put(Token.RUNNING, d.foreground(accent));
        m.put(Token.INFO,    d.foreground(gray));
        m.put(Token.THINKING_PULSE, d.foreground(thinking));

        m.put(Token.TAG_INFO,  d.foreground(gray));
        m.put(Token.TAG_WARN,  d.foreground(amber));
        m.put(Token.TAG_ERROR, d.foreground(failure));

        m.put(Token.TOOL_NAME, d.bold().foreground(accent));
        m.put(Token.TOOL_ARG,  d.foreground(code));
        m.put(Token.FILE_PATH, d.foreground(path));

        m.put(Token.DIFF_ADD,  d.foreground(success));
        m.put(Token.DIFF_DEL,  d.foreground(failure));
        m.put(Token.DIFF_HUNK, d.foreground(path));

        m.put(Token.HEADING,     d.bold());
        m.put(Token.INLINE_CODE, d.foreground(code));
        m.put(Token.CODE_FENCE,  d.foreground(gray));
        m.put(Token.QUOTE,       d.foreground(gray).italic());
        m.put(Token.LINK,        d.foreground(link).underline());

        m.put(Token.STATUS_KEY,       d.foreground(gray));
        m.put(Token.STATUS_VAL,       d);
        m.put(Token.STATUS_MODE_AUTO, d.foreground(gray));
        m.put(Token.STATUS_MODE_PLAN, d.foreground(accent));
        m.put(Token.TIP_AUTO, d.foreground(gray));
        m.put(Token.TIP_PLAN, d.foreground(accent));
        m.put(Token.MODE_INDICATOR_AUTO, d.foreground(gray));
        m.put(Token.MODE_INDICATOR_PLAN, d.foreground(accent));

        m.put(Token.PROMPT_ACTIVE, d.bold().foreground(accent));
        m.put(Token.PROMPT_HISTORY, d.foreground(gray));
        return m;
    }

    private record MapTheme(Map<Token, AttributedStyle> map) implements Theme {
        @Override
        public AttributedStyle styleOf(Token token) {
            return map.getOrDefault(token, AttributedStyle.DEFAULT);
        }
    }
}
