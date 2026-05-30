package madacode.tui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockScopedScreenTest {

    @Test
    void prefixesOnlyTheFirstScrollbackWriteInScope() {
        CapturingScreen delegate = new CapturingScreen();
        BlockScopedScreen screen = new BlockScopedScreen(delegate);

        screen.scrollback("one");
        screen.scrollback("two");
        screen.scrollback(List.of("three", "four"));

        assertEquals(List.of("", "one", "two", "three", "four"), delegate.lines);
    }

    @Test
    void preservesExistingLeadingBlank() {
        CapturingScreen delegate = new CapturingScreen();
        BlockScopedScreen screen = new BlockScopedScreen(delegate);

        screen.scrollback(List.of("", "one"));

        assertEquals(List.of("", "one"), delegate.lines);
    }

    @Test
    void prefixesCommitScrollbackAndPreservesAtomicDelegateCall() {
        CapturingScreen delegate = new CapturingScreen();
        BlockScopedScreen screen = new BlockScopedScreen(delegate);

        screen.commitScrollbackAndSetStatus(List.of("one"), List.of("status"));

        assertEquals(List.of("", "one"), delegate.lines);
        assertEquals(List.of("status"), delegate.status);
        assertEquals(1, delegate.commitCalls);
    }

    private static final class CapturingScreen implements Screen {
        final List<String> lines = new ArrayList<>();
        List<String> status = List.of();
        int commitCalls;

        @Override
        public void scrollback(List<String> lines) {
            this.lines.addAll(lines);
        }

        @Override
        public void commitScrollbackAndSetStatus(List<String> scrollbackLines,
                                                 List<String> newLiveStatus) {
            commitCalls++;
            lines.addAll(scrollbackLines);
            status = newLiveStatus;
        }

        @Override public int width() { return 80; }
        @Override public int height() { return 24; }
        @Override public void flush() {}
    }
}
