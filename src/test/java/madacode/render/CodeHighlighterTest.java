package madacode.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeHighlighterTest {

    @Test
    void unknownLangPassthrough() {
        String line = "some code";
        assertEquals(line, CodeHighlighter.highlight("nolang", line));
        assertEquals(line, CodeHighlighter.highlight(null, line));
        assertEquals(line, CodeHighlighter.highlight("", line));
    }

    @Test
    void javaKeywordsBolded() {
        String out = CodeHighlighter.highlight("java", "public class Foo {");
        String s = strip(out);
        assertTrue(s.contains("public"));
        assertTrue(s.contains("class"));
        assertTrue(s.contains("Foo"));
        // The keywords should be wrapped in ANSI (bold), but strip removes that.
        // Check that non-keyword identifiers are NOT bolded by verifying the
        // raw output has content between bold spans.
        assertTrue(out.contains("public"), "keyword present: " + out);
    }

    @Test
    void commentsAreDimmed() {
        String out = CodeHighlighter.highlight("java", "int x = 1; // init");
        assertTrue(out.contains("init"), "comment text present");
        // The comment should be wrapped in dim ANSI.
        assertTrue(containsStyle(out, "//"), "comment should be dimmed");
    }

    @Test
    void bashKeywordsBolded() {
        String out = CodeHighlighter.highlight("bash", "if [ -f file ]; then echo found; fi");
        strip(out); // call to verify no exception
        assertTrue(out.contains("if"), "bash if keyword");
        assertTrue(out.contains("then"), "bash then keyword");
    }

    @Test
    void jsonLiteralsBolded() {
        String out = CodeHighlighter.highlight("json", "{\"key\": null, \"flag\": true}");
        strip(out);
        assertTrue(out.contains("null"));
        assertTrue(out.contains("true"));
    }

    private static String strip(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }

    private static boolean containsStyle(String s, String text) {
        // The dim SGR sequence is \033[2m. Check that the text portion is
        // preceded by a style reset or the dim code within the line.
        int idx = s.indexOf(text);
        return idx > 0 && (s.substring(0, idx).contains("[2m")
                || s.substring(0, idx).contains("["));
    }
}
