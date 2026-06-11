package madacode.tui.theme;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemesTest {

    @AfterEach
    void restoreThemeState() {
        Themes.configureCapabilities(false, false);
        Themes.setActive("dark");
    }

    @Test
    void namesAndSelectionIncludeLightTheme() {
        assertTrue(Themes.names().contains("dark"));
        assertTrue(Themes.names().contains("light"));
        assertTrue(Themes.setActive("light"));
        assertFalse(Themes.setActive("nope"));
    }

    @Test
    void monochromeThemeDoesNotUseForegroundColors() {
        Themes.configureCapabilities(false, true);

        assertEquals(AttributedStyle.DEFAULT, Themes.dark().styleOf(Token.SUCCESS));
        assertEquals(AttributedStyle.DEFAULT.bold(), Themes.dark().styleOf(Token.TOOL_NAME));
    }
}
