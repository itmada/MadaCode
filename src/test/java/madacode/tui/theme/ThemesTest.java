package madacode.tui.theme;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ThemesTest {

    @Test
    void activeThemeMapsSemanticTokens() {
        Theme theme = Themes.active();

        assertEquals(AttributedStyle.DEFAULT.foreground(71), theme.styleOf(Token.SUCCESS));
        assertEquals(AttributedStyle.DEFAULT.bold().foreground(173), theme.styleOf(Token.TOOL_NAME));
    }

    @Test
    void activeThemeCoversEveryToken() {
        Theme theme = Themes.active();
        for (Token token : Token.values()) {
            assertNotNull(theme.styleOf(token), token.name());
        }
    }
}
