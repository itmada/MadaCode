package madacode.render.turn;

import madacode.tui.TerminalText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TurnStatusRenderableTest {

    @Test
    void rendersDynamicStatusLine() {
        TurnStatusRenderable status = new TurnStatusRenderable("Thinking...", () -> {});
        try {
            assertFalse(status.isFinalized());
            var lines = status.render(80);
            assertEquals(1, lines.size());
            assertTrue(stripAnsi(lines.getFirst()).contains("Thinking..."));
        } finally {
            status.finalizeStatus();
        }
    }

    @Test
    void finalizeRendersEmptyList() {
        TurnStatusRenderable status = new TurnStatusRenderable("Searching...", () -> {});
        status.finalizeStatus();
        assertTrue(status.render(80).isEmpty());
    }

    @Test
    void renderFitsWithinWidth() {
        TurnStatusRenderable status = new TurnStatusRenderable(
                "Searching for \"a very very very very very very long pattern\"...",
                () -> {});
        try {
            String line = status.render(20).getFirst();
            assertTrue(TerminalText.displayWidth(stripAnsi(line)) <= 20, line);
        } finally {
            status.finalizeStatus();
        }
    }

    @Test
    void modeProvidesFallbackMessageWhenBlank() {
        TurnStatusRenderable status = new TurnStatusRenderable("", TurnStatusRenderable.Mode.TOOL_USE, () -> {});
        try {
            assertTrue(stripAnsi(status.render(80).getFirst()).contains("Working..."));
            status.updateMessage("", TurnStatusRenderable.Mode.REQUESTING);
            assertTrue(stripAnsi(status.render(80).getFirst()).contains("Requesting..."));
        } finally {
            status.finalizeStatus();
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
