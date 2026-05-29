package madacode.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalTextTest {

    @Test
    void displayWidthIgnoresAnsiAndCountsWideCharacters() {
        assertEquals(3, TerminalText.displayWidth("\u001B[31mabc\u001B[0m"));
        assertEquals(4, TerminalText.displayWidth("你好"));
    }

    @Test
    void displayWidthTreatsEmojiSequencesAsSingleCells() {
        assertEquals(2, TerminalText.displayWidth("🌤️"));
        assertEquals(5, TerminalText.displayWidth("🌤️ 昆"));
        assertEquals(4, TerminalText.displayWidth("🌤️🌧️"));
        assertEquals(2, TerminalText.displayWidth("👨‍💻"));
        assertEquals(2, TerminalText.displayWidth("👍🏽"));
        assertEquals(2, TerminalText.displayWidth("🇨🇳"));
        assertEquals(2, TerminalText.displayWidth("1️⃣"));
    }

    @Test
    void truncateMiddleDoesNotSplitEmojiSequence() {
        String out = TerminalText.truncateMiddle("abc🌤️def", 6);
        assertTrue(TerminalText.displayWidth(out) <= 6, out);
        assertTrue(!out.contains("\uFE0F") || out.contains("🌤️"), out);
    }

    @Test
    void truncateMiddleFitsColumnBudget() {
        String out = TerminalText.truncateMiddle("abcdefghijklmnop", 8);
        assertTrue(out.contains("…"), out);
        assertTrue(TerminalText.displayWidth(out) <= 8, out);
    }
}
