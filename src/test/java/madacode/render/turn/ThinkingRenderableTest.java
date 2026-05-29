package madacode.render.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThinkingRenderableTest {

    @Test
    void shouldShowSpinnerWhenRunning() {
        ThinkingRenderable r = new ThinkingRenderable(() -> {});
        assertFalse(r.isFinalized());
        var lines = r.render(80);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Thinking"));
    }

    @Test
    void shouldRenderEmptyAfterFinalize() {
        ThinkingRenderable r = new ThinkingRenderable(() -> {});
        r.finalizeThinking();
        assertTrue(r.isFinalized());
        assertTrue(r.render(80).isEmpty());
    }

    @Test
    void shouldAnimateFrames() throws InterruptedException {
        ThinkingRenderable r = new ThinkingRenderable(() -> {});
        String first = r.render(80).get(0);
        Thread.sleep(150);
        String second = r.render(80).get(0);
        // After 150ms the frame should have changed (120ms per frame)
        assertNotEquals(first, second, "spinner frame should change over time");
        r.finalizeThinking();
    }
}
