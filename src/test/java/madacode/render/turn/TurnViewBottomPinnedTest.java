package madacode.render.turn;

import madacode.tui.Screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurnViewBottomPinnedTest {

    @Test
    void pinnedPanelRendersAtBottomOfLiveRegion() {
        CaptureScreen screen = new CaptureScreen();
        TurnView view = new TurnView(screen);

        view.setBottomPinned(new FixedRenderable(List.of("PLAN"), false));
        view.flushNow();

        assertEquals(List.of("PLAN"), screen.live);
    }

    @Test
    void pinnedPanelSitsBelowLiveItemsWithSeparator() {
        CaptureScreen screen = new CaptureScreen();
        TurnView view = new TurnView(screen);

        view.add(new FixedRenderable(List.of("LIVE"), false));
        view.setBottomPinned(new FixedRenderable(List.of("PLAN"), false));
        view.flushNow();

        assertEquals(List.of("LIVE", "", "PLAN"), screen.live);
    }

    @Test
    void pinnedPanelDoesNotPinFinalizedItemsInLiveRegion() {
        CaptureScreen screen = new CaptureScreen();
        TurnView view = new TurnView(screen);

        // A finalized item above the pinned panel must still spill to scrollback,
        // not get stuck live — this is the whole reason the panel is pinned
        // outside the item flow.
        view.add(new FixedRenderable(List.of("DONE"), true));
        view.setBottomPinned(new FixedRenderable(List.of("PLAN"), false));
        view.flushNow();

        assertEquals(List.of("DONE"), screen.scrollback);
        assertEquals(List.of("PLAN"), screen.live);
    }

    @Test
    void endTurnDropsPinnedPanelFromLiveRegion() {
        CaptureScreen screen = new CaptureScreen();
        TurnView view = new TurnView(screen);

        view.setBottomPinned(new FixedRenderable(List.of("PLAN"), false));
        view.flushNow();
        assertEquals(List.of("PLAN"), screen.live);

        view.endTurn();

        assertEquals(List.of(), screen.live);
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
        // Suppress leading-margin blanks so assertions stay focused on placement.
        @Override public boolean isMarginIssued() { return true; }
    }

    private static final class CaptureScreen implements Screen {
        final List<String> scrollback = new ArrayList<>();
        volatile List<String> live = List.of();

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
        @Override public int height() { return 24; }
        @Override public void flush() {}
    }
}
