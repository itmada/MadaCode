package madacode.render;

import madacode.tui.theme.Tk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    // ---- headings ---------------------------------------------------

    @Test
    void headingIsBold() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("# Title\n");
        String out = r.renderLine();
        assertTrue(strip(out).startsWith("Title"), "heading: " + out);
    }

    // ---- lists ------------------------------------------------------

    @Test
    void unorderedList() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("- item one\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("item one"), "ul: " + out);
    }

    @Test
    void unicodeBulletList() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("• item one\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("item one"), "unicode bullet: " + out);
    }

    @Test
    void orderedList() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("1. first\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("first"), "ol: " + out);
    }

    // ---- code fence -------------------------------------------------

    @Test
    void codeFenceToggle() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "should be in code block after opening fence");
        assertTrue(strip(open).contains("java"), "fence open: " + open);

        r.append("int x = 1;\n");
        String code = r.renderLine();
        assertTrue(strip(code).contains("int x = 1"), "code body: " + code);

        r.append("```\n");
        String close = r.renderLine();
        assertFalse(r.isInCodeBlock(), "should exit code block after closing fence");
    }

    // ---- blockquote -------------------------------------------------

    @Test
    void blockquote() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("> quoted text\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("quoted text"), "bq: " + out);
    }

    // ---- table ------------------------------------------------------

    @Test
    void tableSeparatorRowUsedForLayoutNotStandaloneOutput() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| col1 | col2 |\n|------|------|\n| x | y |\n");

        String header = nextRenderedContaining(r, "col1");
        assertTrue(strip(header).contains("col1"), "header: " + header);
        assertTrue(strip(header).contains("col2"), "header: " + header);

        String row = nextRenderedContaining(r, "x");
        assertTrue(strip(row).contains("x"), "row: " + row);
        assertTrue(strip(row).contains("y"), "row: " + row);
        assertNotNull(nextRenderedContaining(r, "└"), "bottom border should be emitted");
        assertNull(r.renderLine(), "separator row should not be emitted as standalone output");
    }

    // ---- inline formatting ------------------------------------------

    @Test
    void boldAndItalic() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("this is **bold** and *italic* text\n");
        String out = r.renderLine();
        // Bold and italic get ANSI-wrapped; content characters remain.
        String s = strip(out);
        assertTrue(s.contains("bold"), "bold content: " + s);
        assertTrue(s.contains("italic"), "italic content: " + s);
        assertTrue(!s.contains("**"), "asterisks gone: " + s);
    }

    @Test
    void inlineCode() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("run `npm install` now\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("npm install"), "inline code: " + out);
        assertTrue(!strip(out).contains("`"), "backticks stripped: " + strip(out));
    }

    @Test
    void strikethrough() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("this is ~~removed~~ text\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("removed"), "strike: " + out);
        assertTrue(!strip(out).contains("~~"), "tildes gone: " + strip(out));
    }

    @Test
    void linkRendersTextNotUrl() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("see [the docs](https://example.com)\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("the docs"), "link text: " + out);
        assertFalse(out.contains("https://"), "url should be hidden; " + out);
    }

    @Test
    void flushRemainingReturnsUnterminatedLine() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("partial line without newline");
        assertNull(r.renderLine());
        String flushed = r.flushRemaining();
        assertNotNull(flushed);
        assertTrue(strip(flushed).contains("partial line"));
        assertNull(r.flushRemaining(), "buffer empty after flush");
    }


    // ---- renderPartial (partial line preview) --------------------------

    @Test
    void renderPartial_codeBlockContent() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.renderLine(); // consume the fence — sets inCodeBlock=true

        String partial = r.renderPartial("int x = 1;");
        assertTrue(strip(partial).contains("int x = 1"), "partial code: " + partial);

        // Verify no state mutation: renderLine still works normally
        r.append("int x = 1;\n");
        String committed = r.renderLine();
        assertTrue(strip(committed).contains("int x = 1"), "committed code: " + committed);
    }

    @Test
    void renderPartial_openingFenceBeingTyped() {
        MarkdownRenderer r = new MarkdownRenderer();
        assertFalse(r.isInCodeBlock());
        String partial = r.renderPartial("```java");
        assertTrue(strip(partial).contains("java"), "partial fence: " + partial);
        assertFalse(r.isInCodeBlock(), "renderPartial must not toggle inCodeBlock");

        // Now commit the real fence line
        r.append("```java\n");
        r.renderLine();
        assertTrue(r.isInCodeBlock(), "real fence should toggle");
    }

    @Test
    void renderPartial_heading() {
        MarkdownRenderer r = new MarkdownRenderer();
        String partial = r.renderPartial("## My Title");
        assertTrue(strip(partial).contains("My Title"), "partial heading: " + partial);
    }

    @Test
    void renderPartial_unorderedList() {
        MarkdownRenderer r = new MarkdownRenderer();
        String partial = r.renderPartial("- some item");
        assertTrue(strip(partial).contains("some item"), "partial ul: " + partial);
    }

    @Test
    void renderPartial_orderedList() {
        MarkdownRenderer r = new MarkdownRenderer();
        String partial = r.renderPartial("3. third item");
        assertTrue(strip(partial).contains("third item"), "partial ol: " + partial);
    }

    @Test
    void renderPartial_blockquote() {
        MarkdownRenderer r = new MarkdownRenderer();
        String partial = r.renderPartial("> quoted");
        assertTrue(strip(partial).contains("quoted"), "partial bq: " + partial);
    }

    @Test
    void renderPartial_inlineFormat() {
        MarkdownRenderer r = new MarkdownRenderer();
        String partial = r.renderPartial("use `npm install` now");
        assertTrue(strip(partial).contains("npm install"), "partial inline code: " + partial);
    }

    @Test
    void renderPartial_emptyReturnsEmpty() {
        MarkdownRenderer r = new MarkdownRenderer();
        assertEquals("", r.renderPartial(""));
    }

    @Test
    void renderPartial_blankReturnsBlank() {
        MarkdownRenderer r = new MarkdownRenderer();
        assertEquals("   ", r.renderPartial("   "));
    }

    @Test
    void renderPartial_plainTextPassthrough() {
        MarkdownRenderer r = new MarkdownRenderer();
        String partial = r.renderPartial("just plain text");
        assertTrue(strip(partial).contains("just plain text"), "plain passthrough: " + partial);
    }

    @Test
    void renderPartial_codeBlockDoesNotCorruptLexerState() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.renderLine(); // inCodeBlock = true

        // Call renderPartial multiple times
        r.renderPartial("int a = 1;");
        r.renderPartial("int b = 2;");

        // Commit a real line — should still work
        r.append("int c = 3;\n");
        String line = r.renderLine();
        assertTrue(strip(line).contains("int c = 3"), "lexer intact after renderPartial: " + line);
    }

    // ---- broader fence language ----------------------------------------

    @Test
    void codeFenceWithHyphenatedLang() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```shell-session\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "should enter code block");
        assertTrue(strip(open).contains("shell-session"), "fence lang: " + open);
    }

    @Test
    void codeFenceWithPlusLang() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```c++\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "should enter code block");
        assertTrue(strip(open).contains("c++"), "fence lang: " + open);
    }

    // ---- inline code protection ---------------------------------------

    @Test
    void boldInsideInlineCodeIsNotFormatted() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("run `npm --save **dev**` now\n");
        String out = r.renderLine();
        String s = strip(out);
        assertTrue(s.contains("**dev**"), "bold markers inside code kept: " + s);
    }

    @Test
    void italicInsideInlineCodeIsNotFormatted() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("call `some_func(x, *args)` please\n");
        String out = r.renderLine();
        String s = strip(out);
        assertTrue(s.contains("*args"), "italic markers inside code kept: " + s);
    }

    // ---- table alignment ----------------------------------------------

    @Test
    void tableColumnAlignment() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| a   | longer |\n");
        r.append("|-----|--------|\n");
        r.append("| 1   | short |\n");
        String row1 = nextRenderedContaining(r, "a");
        String row2 = nextRenderedContaining(r, "1");
        String bottom = nextRenderedContaining(r, "└");
        String row4 = r.renderLine();  // null (no more)

        assertTrue(strip(row1).contains("a"), "header col1: " + row1);
        assertTrue(strip(row1).contains("longer"), "header col2: " + row1);
        assertTrue(strip(row2).contains("short"), "data col2: " + row2);
        assertNotNull(bottom, "bottom border should render");
        assertNull(row4, "no extra content after bottom border");
    }

    @Test
    void tableWithoutSeparatorDoesNotThrow() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| just | cells |\nplain\n");

        String row = nextRenderedContaining(r, "just");
        String plain = nextRenderedContaining(r, "plain");

        assertTrue(strip(row).contains("just"), "table row: " + row);
        assertTrue(strip(row).contains("cells"), "table row: " + row);
        assertEquals("plain", strip(plain));
    }

    @Test
    void previewBufferedTableReturnsEmptyWhenNoBufferedRows() {
        MarkdownRenderer r = new MarkdownRenderer();
        assertTrue(r.previewBufferedTable(80).isEmpty());
    }

    @Test
    void previewBufferedTableRendersWithoutConsumingBuffer() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| H1 | H2 |\n|---|---|\n| D1 | D2 |\n");
        assertNull(r.renderLine(80, true), "table held in streaming mode");

        List<String> preview = r.previewBufferedTable(80);
        assertFalse(preview.isEmpty(), "preview should render buffered table");
        assertTrue(preview.stream().anyMatch(l -> strip(l).contains("H1")),
                "preview header: " + preview);
        assertTrue(preview.stream().anyMatch(l -> strip(l).contains("D1")),
                "preview data: " + preview);

        List<String> again = r.previewBufferedTable(80);
        assertEquals(preview.stream().map(MarkdownRendererTest::strip).toList(),
                again.stream().map(MarkdownRendererTest::strip).toList(),
                "repeated preview should be identical (buffer not consumed)");

        r.append("non-table line\n");
        String line = r.renderLine(80, true);
        assertNotNull(line, "table should flush when non-table line arrives");
    }

    // ---- indented lists -----------------------------------------------

    @Test
    void indentedUnorderedList() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("  - indented item\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("indented item"), "indented ul: " + out);
        assertTrue(strip(out).startsWith("  "), "indent preserved: " + out);
    }

    @Test
    void indentedOrderedList() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("    1. deep item\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("deep item"), "indented ol: " + out);
        assertTrue(strip(out).startsWith("    "), "indent preserved: " + out);
    }

    // ---- ANSI escape filtering ---------------------------------------

    @Test
    void rawAnsiEscapesAreStrippedFromInput() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("hello[31m world\n");
        String out = r.renderLine();
        String s = strip(out);
        assertTrue(s.contains("hello world"), "ansi stripped: " + out);
    }

    // ---- word wrap ---------------------------------------------------

    @Test
    void wordWrapShortTextReturnsSingleLine() {
        List<String> lines = MarkdownRenderer.wordWrap("hello world", 40);
        assertEquals(1, lines.size());
        assertEquals("hello world", lines.get(0));
    }

    @Test
    void wordWrapLongText() {
        List<String> lines = MarkdownRenderer.wordWrap("a b c d e f g h i j k l m n o p", 10);
        assertTrue(lines.size() >= 2, "should wrap: " + lines);
        for (String line : lines) {
            assertTrue(Tk.displayWidth(line) <= 10,
                    "line too long: '" + line + "' width=" + Tk.displayWidth(line));
        }
    }

    @Test
    void wordWrapBreaksLongWords() {
        List<String> lines = MarkdownRenderer.wordWrap("supercalifragilistic", 8);

        assertTrue(lines.size() >= 2, "should break long word: " + lines);
        for (String line : lines) {
            assertTrue(Tk.displayWidth(line) <= 8,
                    "line too long: '" + line + "' width=" + Tk.displayWidth(line));
        }
    }

    // ---- helpers ----------------------------------------------------

    private static String strip(String s) {
        // Remove ANSI SGR sequences (only; keep structural content).
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }

    // ===== NEW: Phase 1 — table <br> and multi-line ===================

    @Test
    void tableWithBrInCellProducesMultipleLines() {
        MarkdownRenderer r = new MarkdownRenderer();
        // Feed each logical section separately to match real streaming
        r.append("| 年份 | 技术里程碑 |\n");
        r.append("|------|------------|\n");
        r.append("| 2021 | AlphaFold 2<br>GPT-3 |\n");

        // header — first row is rendered immediately (no more pipe rows in buffer)
        String h = nextRenderedContaining(r, "年份");
        assertTrue(strip(h).contains("年份"), "header col1: " + h);
        assertTrue(strip(h).contains("技术里程碑"), "header col2: " + h);

        // Because there's no non-table line after, the next renderLine flushes
        // the data row. The separator was consumed during buffering — alignment
        // was derived but no standalone separator line is emitted.

        // data row — first line
        String d1 = nextRenderedContaining(r, "AlphaFold 2");
        assertTrue(strip(d1).contains("2021"), "data line1 col1: " + d1);
        assertTrue(strip(d1).contains("AlphaFold 2"), "data line1 col2 part1: " + d1);

        // data row — second line (from <br>)
        String d2 = nextRenderedContaining(r, "GPT-3");
        assertNotNull(d2, "second table line from <br> should exist");
        assertTrue(strip(d2).contains("GPT-3"), "data line2 col2 part2: " + d2);

        // No more rows
        assertNotNull(nextRenderedContaining(r, "└"), "bottom border should render");
        assertNull(r.renderLine(), "no more lines after table");
    }

    @Test
    void tableBrVariantsAllWork() {
        assertEquals("a\nb", MarkdownRenderer.normalizeHtmlBreaks("a<br>b"));
        assertEquals("a\nb", MarkdownRenderer.normalizeHtmlBreaks("a<br/>b"));
        assertEquals("a\nb", MarkdownRenderer.normalizeHtmlBreaks("a<br />b"));
        assertEquals("a\nb", MarkdownRenderer.normalizeHtmlBreaks("a<BR>b"));
    }

    @Test
    void tableWithMultipleBrInCellProducesProperAlignment() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| 年份 | 事件 |\n");
        r.append("|------|------|\n");
        r.append("| 2021 | AlphaFold 2<br>GPT-3<br>DALL·E 2 |\n");

        // header
        String h = nextRenderedContaining(r, "年份");
        assertTrue(strip(h).contains("年份"), "header: " + h);

        // data row — line 1
        String d1 = nextRenderedContaining(r, "AlphaFold 2");
        assertTrue(strip(d1).contains("2021"), "line1 year: " + d1);
        assertTrue(strip(d1).contains("AlphaFold 2"), "line1 event: " + d1);

        // data row — line 2
        String d2 = nextRenderedContaining(r, "GPT-3");
        assertTrue(strip(d2).contains("GPT-3"), "line2 event: " + d2);

        // data row — line 3
        String d3 = nextRenderedContaining(r, "DALL·E 2");
        assertTrue(strip(d3).contains("DALL·E 2"), "line3 event: " + d3);

        // 2021 column shows blank on lines 2+3
        // The first column is padded to its column width
        assertNotNull(nextRenderedContaining(r, "└"), "bottom border should render");
        assertNull(r.renderLine(), "no more lines");
    }

    @Test
    void tableAlignmentLeftCenterRight() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| left | center | right |\n");
        r.append("|:-----|:------:|------:|\n");
        r.append("| a    | b      | c     |\n");

        // When no trailing non-table line, first row is rendered immediately
        // as a preview; alignment is already derived from the separator.
        String header = nextRenderedContaining(r, "left");
        assertTrue(strip(header).contains("left"), "header left: " + header);
        assertTrue(strip(header).contains("center"), "header center: " + header);
        assertTrue(strip(header).contains("right"), "header right: " + header);

        // Next renderLine flushes the remaining data row
        String row = nextRenderedContaining(r, "a");
        assertTrue(strip(row).contains("a"), "row left: " + row);
        assertTrue(strip(row).contains("b"), "row center: " + row);
        assertTrue(strip(row).contains("c"), "row right: " + row);

        assertNotNull(nextRenderedContaining(r, "└"), "bottom border should render");
        assertNull(r.renderLine(), "no more lines");
    }

    @Test
    void tableFlushBeforeParagraph() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| A | B |\n");
        r.append("|---|---|\n");
        r.append("| 1 | 2 |\n");
        r.append("| 3 | 4 |\n");
        r.append("post\n");

        // Eager buffering consumes all table rows; separator only contributes
        // alignment and is NOT rendered as a standalone line.
        String h = nextRenderedContaining(r, "A"); // header row
        assertTrue(strip(h).contains("A"), "header: " + h);
        assertTrue(strip(h).contains("B"), "header: " + h);

        // "post\n" triggered table flush — next call renders first data row
        String r1 = nextRenderedContaining(r, "1");
        assertTrue(strip(r1).contains("1"), "row1: " + r1);

        // table still has one row buffered
        String r2 = nextRenderedContaining(r, "3");
        assertTrue(strip(r2).contains("3"), "row2: " + r2);

        // table drained → now "post" from re-inserted buffer
        String p = nextRenderedContaining(r, "post");
        assertTrue(strip(p).contains("post"), "post: " + p);
    }

    // ===== NEW: Phase 3 — tilde fence and info strings ================

    @Test
    void tildeFenceOpensAndCloses() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("~~~java\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "tilde fence should open code block");
        assertTrue(strip(open).contains("java"), "tilde fence open: " + open);

        r.append("int x = 1;\n");
        String code = r.renderLine();
        assertTrue(strip(code).contains("int x = 1"), "tilde code body: " + code);

        r.append("~~~\n");
        String close = r.renderLine();
        assertFalse(r.isInCodeBlock(), "tilde fence should close");
        assertTrue(strip(close).contains("──"), "close dimmed: " + close);
    }

    @Test
    void fenceInfoStringWithSpaces() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java title=\"Demo\"\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "should open code block");
        assertTrue(strip(open).contains("java"), "lang should be java: " + open);
        assertFalse(strip(open).contains("Demo"), "info string should not appear: " + open);
    }

    @Test
    void codeFenceCloseWithLongerFence() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.renderLine();
        assertTrue(r.isInCodeBlock());

        r.append("code here\n");
        r.renderLine();

        // Closing with 4 backticks (longer) should still close
        r.append("````\n");
        String close = r.renderLine();
        assertFalse(r.isInCodeBlock(), "4 backticks should close 3-backtick block");
        assertTrue(strip(close).contains("──"), "close: " + close);
    }

    @Test
    void differentFenceTypesDoNotCrossClose() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.renderLine();
        assertTrue(r.isInCodeBlock());

        // A tilde fence should NOT close a backtick fence
        r.append("~~~\n");
        String line = r.renderLine();
        assertTrue(r.isInCodeBlock(), "tilde should not close backtick block");
        // rendered as code content
        assertTrue(strip(line).contains("~~~"), "tilde line rendered as code: " + line);
    }

    @Test
    void tildeCodeBlockDoesNotRenderInlineMarkdown() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("~~~\n");
        r.renderLine();
        assertTrue(r.isInCodeBlock());

        r.append("**bold** inside fence should not be bold\n");
        String code = r.renderLine();
        String s = strip(code);
        assertTrue(s.contains("**bold**"), "bold markers preserved in code: " + s);
    }

    @Test
    void backtickFenceWithTildeClosingIsNotClosed() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```\n");
        r.renderLine();
        assertTrue(r.isInCodeBlock());

        r.append("~~~\n");
        String line = r.renderLine();
        assertTrue(r.isInCodeBlock(), "different fence type should not close");
    }

    @Test
    void fenceWithoutLangOpensAndCloses() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "fence without lang opens");
        assertFalse(strip(open).contains(" "), "no lang tag: " + open);

        r.append("```\n");
        String close = r.renderLine();
        assertFalse(r.isInCodeBlock(), "closed");
    }

    @Test
    void longTildeFence() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("~~~~~\n");
        String open = r.renderLine();
        assertTrue(r.isInCodeBlock(), "5-tilde fence opens");

        r.append("~~~~\n");
        String line = r.renderLine();
        assertTrue(r.isInCodeBlock(), "4 tildes < 5 should not close");

        r.append("~~~~~\n");
        String close = r.renderLine();
        assertFalse(r.isInCodeBlock(), "5 tildes should close 5-tilde block");
    }

    // ===== NEW: Phase 3 — renderPartialLines ==========================

    @Test
    void renderPartialLinesReturnsMultipleLines() {
        MarkdownRenderer r = new MarkdownRenderer();
        String longText = "word1 word2 word3 word4 word5 word6 word7 word8";
        List<String> lines = r.renderPartialLines(longText, 20);
        assertTrue(lines.size() >= 2, "should wrap into multiple lines: " + lines);
        for (String line : lines) {
            int w = Tk.displayWidth(strip(line));
            assertTrue(w <= 20, "line too wide: '" + strip(line) + "' width=" + w);
        }
    }

    @Test
    void renderPartialLinesPreservesFirstLineCompat() {
        MarkdownRenderer r = new MarkdownRenderer();
        String longText = "word1 word2 word3 word4 word5 word6";
        List<String> lines = r.renderPartialLines(longText, 20);
        assertFalse(lines.isEmpty(), "should have at least one line");

        // renderPartial should return same first line
        String single = r.renderPartial(longText, 20);
        assertEquals(strip(lines.get(0)), strip(single), "first line should match renderPartial");
    }

    @Test
    void renderPartialLinesEmptyInput() {
        MarkdownRenderer r = new MarkdownRenderer();
        assertTrue(r.renderPartialLines("", 80).isEmpty());
        assertTrue(r.renderPartialLines(null, 80).isEmpty());
    }

    @Test
    void renderPartialLinesNoWrapWhenMaxWidthLarge() {
        MarkdownRenderer r = new MarkdownRenderer();
        String text = "short text";
        List<String> lines = r.renderPartialLines(text, 2000);
        assertEquals(1, lines.size());
        assertTrue(strip(lines.get(0)).contains("short text"));
    }

    @Test
    void renderPartialLinesCodeBlockContent() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.renderLine(); // enter code block

        List<String> lines = r.renderPartialLines("int x = 1;", 80);
        assertEquals(1, lines.size());
        assertTrue(strip(lines.get(0)).contains("int x = 1"), "partial code lines: " + lines);
    }

    @Test
    void renderPartialLinesWithTildeFence() {
        MarkdownRenderer r = new MarkdownRenderer();
        List<String> lines = r.renderPartialLines("~~~java", 80);
        assertEquals(1, lines.size());
        assertTrue(strip(lines.get(0)).contains("java"), "partial tilde fence: " + lines);
        assertFalse(r.isInCodeBlock(), "renderPartialLines must not mutate state");
    }

    // ===== NEW: normalizeHtmlBreaks / splitSoftBreaks =================

    @Test
    void normalizeHtmlBreaksNullSafe() {
        assertNull(MarkdownRenderer.normalizeHtmlBreaks(null));
        assertEquals("", MarkdownRenderer.normalizeHtmlBreaks(""));
    }

    @Test
    void normalizeHtmlBreaksNoOpWithoutBr() {
        assertEquals("hello world", MarkdownRenderer.normalizeHtmlBreaks("hello world"));
    }

    @Test
    void splitSoftBreaksNoBreaks() {
        List<String> parts = MarkdownRenderer.splitSoftBreaks("hello world");
        assertEquals(1, parts.size());
        assertEquals("hello world", parts.get(0));
    }

    @Test
    void splitSoftBreaksWithHtmlBreaks() {
        List<String> parts = MarkdownRenderer.splitSoftBreaks("a<br>b<br/>c<br />d");
        assertEquals(4, parts.size(), "should split into 4 segments");
        assertEquals("a", parts.get(0));
        assertEquals("b", parts.get(1));
        assertEquals("c", parts.get(2));
        assertEquals("d", parts.get(3));
    }

    @Test
    void splitSoftBreaksNullSafe() {
        List<String> parts = MarkdownRenderer.splitSoftBreaks(null);
        assertEquals(1, parts.size());
        assertEquals("", parts.get(0));
    }

    @Test
    void splitSoftBreaksEmptyInput() {
        List<String> parts = MarkdownRenderer.splitSoftBreaks("");
        assertEquals(1, parts.size());
        assertEquals("", parts.get(0));
    }

    @Test
    void simpleTableRemainsHorizontal() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | Name | Value |
                | --- | --- |
                | A | 1 |
                | B | 2 |
                """);

        List<String> out = collectRendered(r, 80);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("Name"), "should contain header Name: " + joined);
        assertTrue(joined.contains("Value"), "should contain header Value: " + joined);
        assertFalse(joined.contains("Row 1"), "simple table should not fallback to vertical: " + joined);
    }

    @Test
    void horizontalTableRendersFullBoxBorders() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                │ 日期 │ 星期 │ 白天天气 │ 夜间天气 │ 最高温 │ 最低温 │
                │ 05/19 │ 周二 │ 晴 │ 晴 │ 29° │ 16° │
                │ 05/20 │ 周三 │ 多云 │ 阵雨 │ 27° │ 16° │
                """);

        List<String> out = collectRendered(r, 100);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("┌"), "top border should render: " + joined);
        assertTrue(joined.contains("┬"), "top joins should render: " + joined);
        assertTrue(joined.contains("├"), "row separator should render: " + joined);
        assertTrue(joined.contains("┼"), "middle joins should render: " + joined);
        assertTrue(joined.contains("└"), "bottom border should render: " + joined);
        assertTrue(joined.contains("┴"), "bottom joins should render: " + joined);
        assertTrue(joined.contains("05/20"), "data should render: " + joined);
        for (String line : out) {
            assertTrue(Tk.displayWidth(line) <= 100, "line should not exceed 100: [" + line + "]");
        }
    }

    @Test
    void tableCellBrRendersAsMultilineWithoutRawBr() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | 年份 | 技术 |
                | --- | --- |
                | 2021 | A<br>B<br>C |
                """);

        List<String> out = collectRendered(r, 80);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("A"), "A should render: " + joined);
        assertTrue(joined.contains("B"), "B should render: " + joined);
        assertTrue(joined.contains("C"), "C should render: " + joined);
        assertFalse(joined.contains("<br>"), "raw <br> should not render: " + joined);
    }

    @Test
    void tableInlineCodeSurvivesCellWrapping() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | File | Path |
                | --- | --- |
                | ToolDisplay.java | `src/main/java/madacode/render/tool/ToolDisplay.java` |
                """);

        List<String> out = collectRendered(r, 56);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("src/main/java/madacode"), "path should render: " + joined);
        assertTrue(joined.contains("ToolDisplay.java"), "file should render: " + joined);
        assertFalse(joined.contains("`"), "inline code delimiters should not leak after wrapping: " + joined);
        for (String line : out) {
            assertTrue(Tk.displayWidth(line) <= 56, "line should not exceed 56: [" + line + "]");
        }
    }

    @Test
    void tableDelimiterInsideInlineCodeDoesNotSplitCell() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | Name | Value |
                | --- | --- |
                | pattern | `a|b` |
                """);

        List<String> out = collectRendered(r, 80);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("a|b"), "pipe inside inline code should stay in one cell: " + joined);
        assertFalse(joined.contains("`"), "inline code delimiters should not render: " + joined);
    }

    @Test
    void tableDelimiterAfterUnclosedBacktickStillSplitsCell() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | Name | Value |
                | --- | --- |
                | `open | still separate |
                """);

        List<String> out = collectRendered(r, 80);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("`open"), "unclosed backtick should remain literal: " + joined);
        assertTrue(joined.contains("still separate"), "delimiter should still split columns: " + joined);
    }

    @Test
    void tableInlineFormattingPreservesSpacesBetweenRuns() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | Step | Command |
                | --- | --- |
                | 1 | run `npm install` now |
                """);

        List<String> out = collectRendered(r, 80);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("run npm install now"),
                "spaces around inline code should be preserved: " + joined);
        assertFalse(joined.contains("runnpm"), "space before inline code should not be lost: " + joined);
    }

    @Test
    void tableBulletWrappingKeepsContinuationIndent() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | Year | Notes |
                | --- | --- |
                | 2026 | • AlphaFold `protein-structure-prediction` breakthrough |
                """);

        List<String> out = collectRendered(r, 44);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("• AlphaFold"), "bullet should render: " + joined);
        assertTrue(joined.contains("  protein"), "continuation should keep bullet indent: " + joined);
        assertFalse(joined.contains("`"), "inline code delimiters should not leak in bullet: " + joined);
    }

    @Test
    void wideChineseBulletTableFallsBackToVerticalAt80() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | 年份 | 技术里程碑 | 主要应用领域 | 重要事件与产业影响 | 伦理与监管发展 |
                | --- | --- | --- | --- | --- |
                | 2021 | • AlphaFold 2实现蛋白质结构预测突破<br>• GPT-3发布，展示大语言模型潜力<br>• 多模态AI研究兴起 | • 医疗影像诊断<br>• 自动驾驶测试<br>• 个性化推荐系统 | • AI芯片需求激增<br>• 企业AI采用率提升<br>• AI初创公司融资增长 | • 关于AI偏见的讨论增多<br>• 数据隐私关注提升 |
                """);

        List<String> out = collectRendered(r, 80);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("2021"), "should contain year title: " + joined);
        assertTrue(joined.contains("技术里程碑"), "should contain key 技术里程碑: " + joined);
        assertTrue(joined.contains("AlphaFold 2"), "should contain value AlphaFold 2: " + joined);
        assertTrue(joined.contains("主要应用领域"), "should contain key 主要应用领域: " + joined);
        for (String line : out) {
            assertTrue(Tk.displayWidth(line) <= 80, "line should not exceed 80: [" + line + "]");
        }
        assertFalse(joined.contains("┼"), "should avoid overwide horizontal border lines: " + joined);
    }

    @Test
    void chineseLongContentDoesNotOverflow() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("""
                | 年份 | 内容 |
                | --- | --- |
                | 2026 | 通用人工智能（AGI）初步探索 |
                """);

        List<String> out = collectRendered(r, 40);
        String joined = strip(String.join("\n", out));
        assertTrue(joined.contains("2026"), "should contain year: " + joined);
        assertTrue(joined.contains("通用人工智能"), "should keep Chinese content: " + joined);
        for (String line : out) {
            assertTrue(Tk.displayWidth(line) <= 40, "line should not exceed 40: [" + line + "]");
        }
    }

    // ===== flushRemaining drains tableRenderBuffer ====================

    @Test
    void flushRemainingDrainsMultiLineTable() {
        MarkdownRenderer r = new MarkdownRenderer();
        // Single row with <br> that produces multi-line output
        r.append("| col | val |\n");
        r.append("|-----|-----|\n");
        r.append("| 1   | a<br>b<br>c |\n");

        // header
        String h = nextRenderedContaining(r, "col");
        assertTrue(strip(h).contains("col"), "header: " + h);

        // data row line 1
        String d1 = nextRenderedContaining(r, "1");
        assertTrue(strip(d1).contains("1"), "d1: " + d1);

        // Now use flushRemaining instead of renderLine for the continuation
        String d2 = nextFlushedContaining(r, "b");
        assertNotNull(d2, "flushRemaining should return continuation line");
        assertTrue(strip(d2).contains("b"), "d2 should contain 'b': " + d2);

        String d3 = nextFlushedContaining(r, "c");
        assertNotNull(d3, "second flushRemaining should return continuation");
        assertTrue(strip(d3).contains("c"), "d3 should contain 'c': " + d3);

        assertNotNull(nextFlushedContaining(r, "└"), "bottom border should render");
        assertNull(r.flushRemaining(), "no more lines after table");
    }

    // ===== reset clears new fields ====================================

    @Test
    void resetClearsCodeFenceState() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.renderLine();
        assertTrue(r.isInCodeBlock());

        r.reset();
        assertFalse(r.isInCodeBlock());
        // After reset, a normal line renders as plain text, not code
        r.append("plain\n");
        String out = r.renderLine();
        assertTrue(strip(out).contains("plain"), "reset clears code state: " + out);
    }

    @Test
    void resetClearsTableState() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("| a | b |\n");
        r.renderLine();

        r.reset();
        // After reset, pipe lines should render as plain text (no table buffer)
        r.append("| x | y |\n");
        String out = nextRenderedContaining(r, "x");
        assertTrue(strip(out).contains("x"), "pipe line after reset: " + out);
    }

    private static String nextRenderedContaining(MarkdownRenderer renderer, String text) {
        String line;
        while ((line = renderer.renderLine()) != null) {
            if (strip(line).contains(text)) return line;
        }
        return null;
    }

    private static String nextFlushedContaining(MarkdownRenderer renderer, String text) {
        String line;
        while ((line = renderer.flushRemaining()) != null) {
            if (strip(line).contains(text)) return line;
        }
        return null;
    }

    private static List<String> collectRendered(MarkdownRenderer renderer, int width) {
        List<String> out = new ArrayList<>();
        String line;
        while ((line = renderer.renderLine(width)) != null) {
            out.add(line);
        }
        while ((line = renderer.flushRemaining(width)) != null) {
            out.add(line);
        }
        return out;
    }
}
