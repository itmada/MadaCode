package madacode.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Screen wrapper for command-style renderers that write a logical block in
 * several smaller calls. Scrollback writes are buffered and committed once.
 *
 * <p>This lets legacy command code keep using {@link #scrollback(List)} while
 * the outer REPL still enforces one bottom boundary per command result.
 */
public final class BlockCommitScreen implements Screen {

    private final Screen delegate;
    private final List<String> pendingScrollback = new ArrayList<>();

    public BlockCommitScreen(Screen delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public synchronized void scrollback(List<String> lines) {
        if (!lines.isEmpty()) {
            pendingScrollback.addAll(lines);
        }
    }

    @Override
    public synchronized void commitBlock(List<String> lines) {
        scrollback(lines);
    }

    public synchronized void commitBlock() {
        if (pendingScrollback.isEmpty()) {
            return;
        }
        delegate.commitBlock(pendingScrollback);
        pendingScrollback.clear();
    }

    @Override
    public synchronized void ensureScrollbackBoundary() {
        if (pendingScrollback.isEmpty() || pendingScrollback.getLast().isEmpty()) {
            return;
        }
        pendingScrollback.add("");
    }

    @Override
    public void notifyAsync(List<String> lines) {
        delegate.commitAsyncBlock(lines);
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
    public synchronized void commitScrollbackAndSetStatus(
            List<String> scrollbackLines,
            List<String> newLiveStatus) {
        scrollback(scrollbackLines);
        delegate.setLiveStatus(newLiveStatus);
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
}
