package madacode.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for two design-level fixes:
 *  1. Tight lists must not render with blank lines between items
 *     (trust ListBlock.isTight() instead of re-inferring from node shape).
 *  2. Fenced code blocks must not emit a spurious trailing blank gutter line
 *     (handle the literal's conventional trailing newline).
 */
class ListAndCodeRenderingTest {

    private static List<String> renderAll(String md) {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append(md);
        List<String> out = new ArrayList<>();
        String line;
        while ((line = r.renderLine(80)) != null) out.add(strip(line));
        String tail = r.flushRemaining(80);
        while (tail != null) { out.add(strip(tail)); tail = r.flushRemaining(80); }
        return out;
    }

    private static String strip(String s) {
        return s == null ? null : s.replaceAll("\\e\\[[0-9;]*m", "");
    }

    @Test
    void tightListHasNoBlankLinesBetweenItems() {
        List<String> out = renderAll("- a\n- b\n- c\n");
        // Lines containing the items, in order, with nothing blank between them.
        int ia = indexOfContaining(out, "a");
        int ib = indexOfContaining(out, "b");
        int ic = indexOfContaining(out, "c");
        assertTrue(ia >= 0 && ib == ia + 1 && ic == ib + 1,
                "tight list items must be adjacent (no blank lines): " + out);
    }

    @Test
    void looseListHasBlankLinesBetweenItems() {
        List<String> out = renderAll("- a\n\n- b\n\n- c\n");
        int ia = indexOfContaining(out, "a");
        int ib = indexOfContaining(out, "b");
        assertTrue(ia >= 0 && ib >= 0 && ib > ia + 1,
                "loose list items must be separated by a blank line: " + out);
        assertEquals("", out.get(ia + 1), "blank between loose items: " + out);
    }

    @Test
    void nestedTightListNoBlankLines() {
        List<String> out = renderAll("- top\n  - sub1\n  - sub2\n");
        int s1 = indexOfContaining(out, "sub1");
        int s2 = indexOfContaining(out, "sub2");
        assertTrue(s1 >= 0 && s2 == s1 + 1, "nested tight items adjacent: " + out);
    }

    @Test
    void fencedCodeBlockHasNoTrailingBlankGutterLine() {
        List<String> out = renderAll("```java\nint x = 1;\n```\n");
        int bottom = indexOfContaining(out, "╰");
        assertTrue(bottom >= 0, "closing fence present: " + out);
        String beforeBottom = out.get(bottom - 1);
        assertTrue(beforeBottom.contains("int x = 1;"),
                "line before closing fence must be the code, not a blank gutter: " + out);
    }

    @Test
    void fencedCodeBlockPreservesInternalBlankLine() {
        List<String> out = renderAll("```\na\n\nb\n```\n");
        // Both a and b present, with a gutter blank line between them (not collapsed).
        int ia = indexOfContaining(out, "a");
        int ib = indexOfContaining(out, "b");
        assertTrue(ia >= 0 && ib >= 0 && ib - ia == 2,
                "internal blank line inside code must be preserved: " + out);
    }

    private static int indexOfContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        return -1;
    }
}
