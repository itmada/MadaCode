package madacode.render.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolProgressLineTest {

    @Test
    void constructorsSanitizeControlWhitespace() {
        ToolProgressLine line = ToolProgressLine.activity("  hello\nworld\r  ");
        assertEquals(ToolProgressLine.Kind.ACTIVITY, line.kind());
        assertEquals("hello world", line.text());
    }

    @Test
    void nullTextBecomesBlank() {
        ToolProgressLine line = ToolProgressLine.output(null);
        assertEquals("", line.text());
    }
}
