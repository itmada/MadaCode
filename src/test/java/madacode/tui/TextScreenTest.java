package madacode.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextScreenTest {

    @Test
    void scrollbackPrintsLinesInOrder() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Screen screen = new TextScreen(new PrintStream(buf));

        screen.scrollback("first");
        screen.scrollback(List.of("second", "third"));

        assertEquals("first\nsecond\nthird\n", buf.toString());
    }

    @Test
    void widthHeightHaveSafeFloors() {
        Screen screen = new TextScreen(new PrintStream(new ByteArrayOutputStream()), 5, 1);
        assertEquals(20, screen.width());
        assertEquals(5, screen.height());
    }
}
