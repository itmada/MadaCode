package madacode.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffHighlighterTest {

    @Test
    void emptyDiffProducesEmpty() {
        assertTrue(DiffHighlighter.highlightAndRender("", 10).isEmpty());
        assertTrue(DiffHighlighter.highlightAndRender(null, 10).isEmpty());
    }

    @Test
    void hunkHeaderIsDimmed() {
        List<String> out = DiffHighlighter.highlightAndRender("@@ -1,3 +1,4 @@\n", 10);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("\033"), "hunk should be ANSI-styled");
    }

    @Test
    void unpairedLinesAreStyled() {
        List<String> out = DiffHighlighter.highlightAndRender("-deleted line\n+new line\n", 10);
        assertEquals(2, out.size());
        // Both lines should contain ANSI (red for delete, green for add)
        assertTrue(out.get(0).contains("\033"), "deleted line styled");
        assertTrue(out.get(1).contains("\033"), "added line styled");
    }

    @Test
    void pairedLinesProduceWordLevelDiff() {
        List<String> out = DiffHighlighter.highlightAndRender(
                "-old word keep\n+new word keep\n", 10);
        assertEquals(2, out.size());
        // Content words should be present (ANSI-wrapped)
        assertTrue(out.get(0).contains("old"), "old word present: " + out.get(0));
        assertTrue(out.get(0).contains("keep"), "keep word present: " + out.get(0));
        assertTrue(out.get(1).contains("new"), "new word present: " + out.get(1));
        assertTrue(out.get(1).contains("keep"), "keep word present: " + out.get(1));
        // Both lines should be ANSI-styled
        assertTrue(out.get(0).contains("\033"), "del line styled");
        assertTrue(out.get(1).contains("\033"), "add line styled");
    }

    @Test
    void differringWordsGetCorrectStyles() {
        List<String> out = DiffHighlighter.highlightAndRender(
                "-alpha beta gamma\n+alpha delta gamma\n", 10);
        assertEquals(2, out.size());
        String del = out.get(0), add = out.get(1);
        // "beta" should be in the del line, "delta" in the add line
        assertTrue(del.contains("beta"));
        assertTrue(add.contains("delta"));
        // "alpha" and "gamma" should be in both
        assertTrue(del.contains("alpha"));
        assertTrue(add.contains("gamma"));
        // Both lines must have ANSI styling
        assertTrue(del.contains("\033"));
        assertTrue(add.contains("\033"));
    }

    @Test
    void maxLinesLimitsOutput() {
        List<String> out = DiffHighlighter.highlightAndRender(
                "-a\n+b\n-c\n+d\n-e\n", 3);
        assertTrue(out.size() <= 3, "limited to 3; got " + out.size());
    }

    @Test
    void contextLinesAreNotStyled() {
        List<String> out = DiffHighlighter.highlightAndRender(
                " context line\n", 10);
        assertEquals(1, out.size());
        assertFalse(out.get(0).contains("\033"), "context should not be ANSI-styled");
    }
}
