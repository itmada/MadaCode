package madacode.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Screen wrapper that makes the first scrollback write in a scope start as a
 * visible block, while leaving subsequent writes in that scope untouched.
 */
public final class BlockScopedScreen implements Screen {

    private final Screen delegate;
    private boolean started;

    public BlockScopedScreen(Screen delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public synchronized void scrollback(List<String> lines) {
        if (lines.isEmpty()) return;
        delegate.scrollback(spacedIfFirst(lines));
    }

    @Override
    public void setLiveStatus(List<String> lines) {
        delegate.setLiveStatus(lines);
    }

    @Override
    public void clearLiveStatus() {
        delegate.clearLiveStatus();
    }

    @Override
    public void commitScrollbackAndSetStatus(List<String> scrollbackLines,
                                             List<String> newLiveStatus) {
        delegate.commitScrollbackAndSetStatus(spacedIfFirst(scrollbackLines), newLiveStatus);
    }

    @Override
    public void setLiveModal(List<String> lines) {
        delegate.setLiveModal(lines);
    }

    @Override
    public void clearLiveModal() {
        delegate.clearLiveModal();
    }

    @Override
    public int width() {
        return delegate.width();
    }

    @Override
    public int height() {
        return delegate.height();
    }

    @Override
    public void setCursorVisible(boolean visible) {
        delegate.setCursorVisible(visible);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    private synchronized List<String> spacedIfFirst(List<String> lines) {
        if (lines.isEmpty()) return lines;
        if (started) return lines;
        started = true;
        if (lines.getFirst().isEmpty()) return lines;
        List<String> spaced = new ArrayList<>(lines.size() + 1);
        spaced.add("");
        spaced.addAll(lines);
        return List.copyOf(spaced);
    }
}
