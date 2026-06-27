package madacode.render.turn;

import madacode.tui.Screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnViewLiveViewportTest {

    @Test
    void liveRegionIsCappedToTerminalHeight() {
        CaptureScreen screen = new CaptureScreen(8);
        TurnView view = new TurnView(screen);

        view.add(new FixedRenderable(lines(10), false));
        view.flushNow();

        List<String> live = strip(screen.live);
        assertEquals(6, live.size());
        assertTrue(live.getFirst().contains("5 earlier live lines hidden"));
        assertEquals(List.of("line-6", "line-7", "line-8", "line-9", "line-10"),
                live.subList(1, live.size()));
    }

    @Test
    void endTurnWritesCompleteHistoryWithoutLiveCap() {
        CaptureScreen screen = new CaptureScreen(8);
        TurnView view = new TurnView(screen);

        view.add(new FixedRenderable(lines(10), false));
        view.flushNow();
        view.endTurn();

        List<String> scrollback = strip(screen.scrollback);
        assertEquals(lines(10), scrollback.subList(0, 10));
        assertEquals(List.of(), screen.live);
    }

    private static List<String> lines(int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add("line-" + i);
        }
        return lines;
    }

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(s -> s.replaceAll("\\e\\[[;\\d]*m", "")).toList();
    }

    private static final class FixedRenderable implements Renderable {
        private final List<String> lines;
        private final boolean finalized;

        FixedRenderable(List<String> lines, boolean finalized) {
            this.lines = lines;
            this.finalized = finalized;
        }

        @Override public List<String> render(int maxWidth) { return lines; }
        @Override public boolean isFinalized() { return finalized; }
        @Override public boolean isMarginIssued() { return true; }
    }

    private static final class CaptureScreen implements Screen {
        final List<String> scrollback = new ArrayList<>();
        volatile List<String> live = List.of();
        private final int height;

        CaptureScreen(int height) {
            this.height = height;
        }

        @Override
        public synchronized void scrollback(List<String> lines) {
            scrollback.addAll(lines);
        }

        @Override
        public synchronized void setLiveStatus(List<String> lines) {
            live = List.copyOf(lines);
        }

        @Override
        public synchronized void commitScrollbackAndSetStatus(
                List<String> scrollbackLines, List<String> newLiveStatus) {
            scrollback.addAll(scrollbackLines);
            live = List.copyOf(newLiveStatus);
        }

        @Override public int width() { return 80; }
        @Override public int height() { return height; }
        @Override public void flush() {}
    }
}
