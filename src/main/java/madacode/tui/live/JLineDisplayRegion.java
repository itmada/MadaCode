package madacode.tui.live;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.Status;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Live region backed by {@link Display} for row-level diff redraws.
 *
 * <p>Replaces the DECSC/DECRC ANSI anchor approach of {@link LiveRegion}
 * with JLine's built-in diffing display.  Modal and status layers are
 * preserved; the higher (modal) takes precedence when non-empty.
 */
public final class JLineDisplayRegion {

    private static final long RESIZE_DEBOUNCE_MILLIS = 40L;

    private final Terminal terminal;
    private final PrintWriter writer;
    private final Display display;

    private List<String> modalLines = List.of();
    private List<String> statusLines = List.of();
    private int currentHeight;
    private boolean modalLocked;
    private Status bottomStatus;

    private int lastKnownRows;
    private int lastKnownCols;
    private volatile boolean suspended;
    private volatile Runnable resizeListener;
    private Terminal.SignalHandler prevWinchHandler;
    private Terminal.SignalHandler prevContHandler;

    private final ScheduledExecutorService resizeScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jline-display-resize-debounce");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> pendingResize;

    public JLineDisplayRegion(Terminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        this.terminal = terminal;
        this.writer = terminal.writer();
        this.display = new Display(terminal, false);
        int h = terminal.getHeight();
        int w = terminal.getWidth();
        if (h == 0) h = 24;
        if (w == 0) w = 80;
        display.resize(h, w);
        this.lastKnownRows = h;
        this.lastKnownCols = w;

        this.prevWinchHandler = terminal.handle(Terminal.Signal.WINCH, sig -> onResize());
        this.prevContHandler  = terminal.handle(Terminal.Signal.CONT,  sig -> onResize());
    }

    // ---- public layer API ------------------------------------------------

    public synchronized void setStatus(List<String> lines) {
        statusLines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (!suspended) repaint();
    }

    public synchronized void clearStatus() {
        statusLines = List.of();
        if (!suspended) repaint();
    }

    /**
     * Lock the modal channel — subsequent {@link #setModal} calls are no-op
     * until {@link #unlockModal()} is called.  Used by inline permission
     * to prevent modal overlays from hiding the permission prompt.
     */
    public synchronized void lockModal() {
        modalLocked = true;
    }

    /**
     * Unlock the modal channel.  Safe to call without a prior {@link #lockModal()}.
     */
    public synchronized void unlockModal() {
        modalLocked = false;
        if (!suspended) repaint();
    }

    public synchronized void setModal(List<String> lines) {
        if (modalLocked) return;
        modalLines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (!suspended) repaint();
    }

    public synchronized void clearModal() {
        if (modalLocked) return;
        modalLines = List.of();
        if (!suspended) repaint();
    }

    public synchronized int currentHeight() {
        return currentHeight;
    }

    /**
     * When suspended, resize/repaint do not write to the terminal; only
     * Display's internal dimensions are kept in sync so the next non-suspended
     * paint starts from a correct model. Used during IDLE phase when JLine's
     * LineReader owns the terminal.
     */
    public synchronized void suspend() {
        suspended = true;
        // Force-clear any live content from the terminal before handing off.
        // After this, the terminal has no live region — LineReader is free to
        // paint its own prompt at the natural cursor position.
        modalLines = List.of();
        statusLines = List.of();
        display.update(List.of(), 0);
        display.reset();
        currentHeight = 0;
        writer.flush();
    }

    public synchronized void resume() {
        suspended = false;
        // Re-sync size (terminal may have resized while suspended) and force
        // a full repaint from a clean slate.
        syncSize();
        repaint();
    }

    public synchronized void shutdown() {
        if (prevWinchHandler != null) {
            terminal.handle(Terminal.Signal.WINCH, prevWinchHandler);
            prevWinchHandler = null;
        }
        if (prevContHandler != null) {
            terminal.handle(Terminal.Signal.CONT, prevContHandler);
            prevContHandler = null;
        }

        resizeScheduler.shutdownNow();
        if (bottomStatus != null) {
            bottomStatus.update(List.of()); // restore the full-height scroll region
        }
        modalLines = List.of();
        statusLines = List.of();
        display.update(List.of(), 0);
        writer.flush();
    }

    public void withLock(Runnable action) {
        synchronized (this) {
            action.run();
        }
    }

    /**
     * Write scrollback lines above the live region, restoring live content after.
     *
     * <p>After the {@code println} loop the physical cursor is below where
     * {@link Display} thinks it is, so we call {@code display.reset()} to
     * invalidate its internal state.  The subsequent {@link #repaint()} will
     * then do a full redraw from the current cursor position instead of a
     * broken diff.
     */
    public synchronized void commitScrollback(List<String> lines) {
        commitScrollbackAndSetStatus(lines, null);
    }

    /**
     * Atomically commit scrollback lines and update status in one repaint.
     * Eliminates the intermediate frame where old status is briefly visible.
     *
     * @param scrollbackLines lines to print above the live region
     * @param newStatus       new status lines, or {@code null} to keep current
     */
    public synchronized void commitScrollbackAndSetStatus(
            List<String> scrollbackLines, List<String> newStatus) {
        Objects.requireNonNull(scrollbackLines, "scrollbackLines");
        if (newStatus != null) {
            statusLines = List.copyOf(newStatus);
        }
        if (suspended) {
            // LineReader owns the terminal; route scrollback through plain
            // println. The caller (JLineScreen) is responsible for using
            // lr.printAbove() instead when an active LineReader is present;
            // this branch only runs in the narrow window between phase
            // transitions, where neither owner is fully active.
            for (String line : scrollbackLines) {
                writer.println(line);
            }
            writer.flush();
            return;
        }
        display.update(List.of(), 0);
        for (String line : scrollbackLines) {
            writer.println(line);
        }
        display.reset();
        currentHeight = 0;
        repaint();
    }

    // ---- pinned bottom status footer --------------------------------------

    private Status bottomStatus() {
        if (bottomStatus == null) {
            bottomStatus = Status.getStatus(terminal, true); // null if not an AbstractTerminal
        }
        return bottomStatus;
    }

    /** Rows currently reserved by the pinned footer (0 if none / unsupported terminal). */
    private int bottomStatusSize() {
        return bottomStatus == null ? 0 : bottomStatus.size();
    }

    /**
     * Set the persistent bottom-status footer (mode · model · ctx), pinned to
     * the terminal bottom via JLine {@link Status} (a DECSTBM scroll region).
     *
     * <p>Unlike the {@link #setStatus status} layer, this footer survives BOTH
     * phases: the TURN-phase live region and the IDLE-phase {@link
     * org.jline.reader.LineReader} both render ABOVE it. When the footer's row
     * count changes, the Display is re-sized so it never paints over the footer.
     *
     * <p>Runs under the region lock, so footer writes (which move the cursor via
     * save/restore) never interleave with Display writes. Empty list clears it.
     */
    public synchronized void setBottomStatus(List<AttributedString> lines) {
        Status status = bottomStatus();
        if (status == null) return; // unsupported terminal — degrade silently
        int before = status.size();
        status.update(lines == null ? List.of() : lines);
        if (status.size() != before && syncSize() && !suspended) {
            repaint();
        }
    }

    /** Remove the footer and restore the full-height scroll region. */
    public synchronized void clearBottomStatus() {
        if (bottomStatus == null) return;
        int before = bottomStatus.size();
        bottomStatus.update(List.of());
        if (bottomStatus.size() != before && syncSize() && !suspended) {
            repaint();
        }
    }

    // ---- internals -------------------------------------------------------

    /** Sync Display dimensions with the physical terminal. Returns true if size changed. */
    private boolean syncSize() {
        int curH = terminal.getHeight();
        int curW = terminal.getWidth();
        if (curH == 0) curH = 24;
        if (curW == 0) curW = 80;
        // Reserve the pinned bottom-status rows: the live region renders ABOVE
        // the footer, so the Display only owns (rows - footer) rows. Mirrors
        // LineReaderImpl.displayRows(Status). lastKnownRows tracks these
        // effective rows, so a footer appearing/disappearing also triggers a
        // re-resize even when the physical height is unchanged.
        int effRows = Math.max(1, curH - bottomStatusSize());
        if (effRows == lastKnownRows && curW == lastKnownCols) return false;

        // Discard Display's prior frame model: under fullScreen=false, both
        // shrink and grow can cause the cached oldLines to no longer match
        // the physical terminal (rows reflow, wrap boundaries shift). reset()
        // forces the next update() to be a full repaint with no diff
        // optimization, which is exactly what we want after any size change.
        display.resize(effRows, curW);
        display.reset();
        lastKnownRows = effRows;
        lastKnownCols = curW;
        return true;
    }

    public void onResize() {
        // Signal handler thread — schedule the actual work on the debounce
        // executor so a WINCH storm coalesces to a single repaint. We MUST NOT
        // hold `synchronized(this)` here: doing so would serialize with paint
        // threads under signal pressure and risk priority inversion.
        synchronized (this) {
            if (pendingResize != null && !pendingResize.isDone()) {
                pendingResize.cancel(false);
            }
            try {
                pendingResize = resizeScheduler.schedule(
                        this::handleResizeDebounced,
                        RESIZE_DEBOUNCE_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // scheduler shut down — no-op
            }
        }
    }

    private void handleResizeDebounced() {
        synchronized (this) {
            boolean changed = syncSize();
            if (!suspended && changed) {
                repaint();
            }
        }
        // Resize listener (e.g., TurnView::markDirty) runs OUTSIDE the lock to
        // avoid recursive contention with TurnView's own synchronized paint.
        Runnable rl = resizeListener;
        if (rl != null) rl.run();
    }

    /** Register a callback invoked after every resize signal (even no-op ones). */
    public void setResizeListener(Runnable listener) {
        this.resizeListener = listener;
    }

    private List<String> effectiveLines() {
        if (!modalLines.isEmpty()) return modalLines;
        return statusLines;
    }

    private void repaint() {
        syncSize();

        List<String> next = effectiveLines();
        if (next.isEmpty()) {
            display.update(List.of(), 0);
            currentHeight = 0;
            writer.flush();
            return;
        }

        int width = Math.max(1, lastKnownCols);
        List<AttributedString> visualRows = new ArrayList<>();
        for (String line : next) {
            visualRows.addAll(splitToVisualRows(line, width));
        }
        // targetCursorPos = 0: place cursor at row 0, column 0 of the live region.
        // OS cursor is hidden during TURN, so visual position is irrelevant — but
        // Display uses targetCursorPos as the anchor for its NEXT-FRAME diff, so
        // passing a consistent, well-defined value (0) keeps its internal model accurate.
        display.update(visualRows, 0);
        currentHeight = visualRows.size();
        writer.flush();
    }

    /**
     * Split a logical line (possibly containing ANSI styling) into one or more
     * AttributedString rows, each occupying exactly one visual row at the
     * current terminal width.
     *
     * <p>This is the single source of truth for "how many physical rows does
     * this line take". Both {@link Display#update} input and currentHeight are
     * derived from the same split result, eliminating wrap-model drift.
     */
    private List<AttributedString> splitToVisualRows(String line, int width) {
        AttributedString attr = AttributedString.fromAnsi(line);
        // columnSplitLength(width, true, true) wraps at column boundaries,
        // including a trailing empty row when content ends exactly on a wrap
        // boundary; second arg `includeNewLines`=true preserves explicit \n
        // (we don't expect any since callers pass single logical lines, but
        // belt-and-suspenders); third arg `delayLineWrap`=true matches the
        // behavior of most terminals (cursor sits on last column, doesn't
        // auto-wrap until next char).
        List<AttributedString> rows = attr.columnSplitLength(width, true, true);
        if (rows.isEmpty()) {
            // empty input should still produce one empty visual row when used
            // as a blank-line separator
            rows = List.of(AttributedString.EMPTY);
        }
        return rows;
    }
}
