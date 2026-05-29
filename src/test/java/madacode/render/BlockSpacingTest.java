package madacode.render;

import madacode.tui.Screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockSpacingTest {

    @Test
    void scrollbackBlocksOwnLeadingBlank() {
        CapturingScreen screen = new CapturingScreen();

        BlockSpacing.scrollbackBlock(screen, List.of("one", "two"));

        assertEquals(List.of("", "one", "two"), screen.lines);
    }

    @Test
    void activityBlocksAreReturnedVerbatim() {
        // Activity drawer is its own region — no synthetic leading blank.
        // The bottom region grows naturally and JLineScreen.repaintBottom
        // protects the existing scrollback by scrolling it up first.
        assertEquals(List.of("thinking"),
                BlockSpacing.activityBlock(List.of("thinking")));
    }

    private static final class CapturingScreen implements Screen {
        final List<String> lines = new ArrayList<>();

        @Override
        public void scrollback(List<String> lines) {
            this.lines.addAll(lines);
        }

        @Override public int width() { return 80; }
        @Override public int height() { return 24; }
        @Override public void flush() {}
    }
}
