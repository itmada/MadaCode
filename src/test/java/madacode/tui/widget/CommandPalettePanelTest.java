package madacode.tui.widget;

import madacode.tui.TerminalText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPalettePanelTest {

    @Test
    void viewportKeepsMiddleCursorVisibleInLongInput() {
        String input = "/model claude-sonnet-4-6";
        int cursor = 10;
        CommandPalettePanel.Viewport viewport =
                CommandPalettePanel.viewport(input, cursor, 8);

        assertViewportMatchesInput(input, cursor, 8, viewport);
        assertTrue(viewport.cursorOffset() > 0);
        assertTrue(viewport.cursorOffset() < viewport.text().length());
    }

    @Test
    void viewportTracksCursorNearEndWithoutDroppingIt() {
        String input = "/provider anthropic";
        int cursor = input.length();
        CommandPalettePanel.Viewport viewport =
                CommandPalettePanel.viewport(input, cursor, 6);

        assertViewportMatchesInput(input, cursor, 6, viewport);
        assertTrue(viewport.cursorOffset() == viewport.text().length());
        assertTrue(TerminalText.displayWidth(viewport.text()) <= 5);
    }

    @Test
    void viewportAtShortWidthsStillIncludesCursorWindow() {
        String input = "/model";
        int cursor = 2;
        CommandPalettePanel.Viewport viewport =
                CommandPalettePanel.viewport(input, cursor, 3);

        assertViewportMatchesInput(input, cursor, 3, viewport);
    }

    @Test
    void viewportReservesRoomForCursorWhenInputExactlyFitsWidth() {
        CommandPalettePanel.Viewport viewport =
                CommandPalettePanel.viewport("123456", 6, 6);

        assertTrue(TerminalText.displayWidth(viewport.text()) <= 5);
        assertEquals(viewport.text().length(), viewport.cursorOffset());
    }

    @Test
    void viewportReservesRoomForCursorAtWidthOne() {
        CommandPalettePanel.Viewport viewport =
                CommandPalettePanel.viewport("123", 3, 1);

        assertEquals("", viewport.text());
        assertEquals(0, viewport.cursorOffset());
    }

    @Test
    void viewportReservesRoomForCursorForWideClustersAtEnd() {
        CommandPalettePanel.Viewport viewport =
                CommandPalettePanel.viewport("你你你", 3, 4);

        assertTrue(TerminalText.displayWidth(viewport.text()) <= 2);
        assertEquals(viewport.text().length(), viewport.cursorOffset());
    }

    private static void assertViewportMatchesInput(
            String input, int cursor, int width, CommandPalettePanel.Viewport viewport) {
        assertTrue(TerminalText.displayWidth(viewport.text()) <= width);
        assertTrue(viewport.cursorOffset() >= 0);
        assertTrue(viewport.cursorOffset() <= viewport.text().length());

        if (viewport.cursorOffset() > 0) {
            String left = viewport.text().substring(0, viewport.cursorOffset());
            assertTrue(input.contains(left), "left segment should come from input: " + left);
        }
        if (viewport.cursorOffset() < viewport.text().length()) {
            String right = viewport.text().substring(viewport.cursorOffset());
            assertTrue(input.contains(right), "right segment should come from input: " + right);
        }
    }
}
