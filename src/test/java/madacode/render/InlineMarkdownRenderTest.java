package madacode.render;

import madacode.tui.theme.Tk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineMarkdownRenderTest {

    @Test
    void rendererPreservesInlineAnsiOutputForCommonSyntax() {
        MarkdownRenderer renderer = new MarkdownRenderer();

        List<String> lines = renderAll(renderer,
                "# Heading **bold** *italic* `code` [link](https://example.com) ~~gone~~");

        assertEquals(List.of(
                Tk.heading("Heading "
                        + Tk.bold("bold")
                        + " "
                        + Tk.italic("italic")
                        + " "
                        + Tk.inlineCode("code")
                        + " "
                        + Tk.link("link")
                        + " "
                        + Tk.dim("gone"))
        ), lines);
    }

    @Test
    void rendererWrapsStyledParagraphsWithoutLosingStyles() {
        MarkdownRenderer renderer = new MarkdownRenderer();

        List<String> lines = renderAll(renderer,
                "Alpha **Beta** gamma `delta` epsilon [zeta](https://example.com)", 18);

        assertEquals(List.of(
                "Alpha " + Tk.bold("Beta") + " gamma",
                Tk.inlineCode("delta") + " epsilon " + Tk.link("zeta")
        ), lines);
    }

    @Test
    void rendererPreservesTableCellInlineBreaksAndStyles() {
        MarkdownRenderer renderer = new MarkdownRenderer();

        List<String> lines = renderAll(renderer, """
                | Name | Notes |
                | --- | --- |
                | Item | first<br>**second** |
                """, 40);

        assertEquals(List.of(
                "┌──────┬────────┐",
                "│ Name │ Notes  │",
                "├──────┼────────┤",
                "│ Item │ first  │",
                "│      │ second │",
                "└──────┴────────┘"
        ), stripAnsi(lines));
        assertTrue(lines.get(4).contains(Tk.bold("second")));
    }

    @Test
    void parseUsesCommonmarkInlineRulesForEscapesAndFormatting() {
        InlineMarkdown.Line line = InlineMarkdown.parse("\\*escaped\\* and **bold**");

        assertEquals("*escaped* and bold", InlineMarkdown.plainText(line));
        assertEquals("*escaped* and " + Tk.bold("bold"), InlineMarkdown.render(line));
    }

    @Test
    void parseFallbackForBulletPrefixRemainsPlain() {
        InlineMarkdown.Line line = InlineMarkdown.parse("•");

        assertEquals("•", InlineMarkdown.plainText(line));
        assertEquals("•", InlineMarkdown.render(line));
    }

    private static List<String> renderAll(MarkdownRenderer renderer, String markdown) {
        return renderAll(renderer, markdown, 100);
    }

    private static List<String> renderAll(MarkdownRenderer renderer, String markdown, int width) {
        List<String> lines = new java.util.ArrayList<>();
        renderer.append(markdown);
        String line;
        while ((line = renderer.renderLine(width)) != null) {
            lines.add(line);
        }
        while ((line = renderer.flushRemaining(width)) != null) {
            lines.add(line);
        }
        return lines;
    }

    private static List<String> stripAnsi(List<String> lines) {
        return lines.stream()
                .map(line -> line.replaceAll("\u001B\\[[;\\d]*m", ""))
                .toList();
    }
}
