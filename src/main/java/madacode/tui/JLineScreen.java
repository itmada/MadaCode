package madacode.tui;

import madacode.tui.live.JLineDisplayRegion;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link Screen} backed by a JLine {@link Terminal}.
 *
 * <p>Terminal ownership is managed by an explicit {@link Phase}:
 * <ul>
 *   <li>{@link Phase#TURN} — our {@link JLineDisplayRegion} owns the terminal.
 *       Live content (tool cards, streaming text, permission prompts) is rendered
 *       via the Display. Scrollback flows through the liveRegion when no
 *       LineReader is active.</li>
 *   <li>{@link Phase#IDLE} — JLine's {@link LineReader} owns the terminal.
 *       All live-region operations are no-ops to prevent the two Displays from
 *       conflicting. Scrollback still works (degraded to plain println when
 *       the Display has no content).</li>
 * </ul>
 *
 * <p>Phase transitions are driven by the REPL loop, which is the single
 * coordination point that knows when a turn starts and ends.
 */
public final class JLineScreen implements Screen {

    enum Phase { IDLE, TURN }

    private static final String CSI = "\033[";

    private final Terminal terminal;
    private final PrintWriter writer;
    private final JLineDisplayRegion liveRegion;
    private volatile LineReader activeLineReader;
    private volatile Phase phase = Phase.TURN;
    private volatile boolean composing;
    private int cursorHideDepth = 0;

    public JLineScreen(Terminal terminal) {
        this.terminal = terminal;
        this.writer = terminal.writer();
        this.liveRegion = new JLineDisplayRegion(terminal);
    }

    // ---- Phase lifecycle --------------------------------------------------

    /** Switch to IDLE phase: suspend live region, let JLine's LineReader own the terminal. */
    public void enterIdlePhase() {
        phase = Phase.IDLE;          // gate first so pending setLiveStatus sees IDLE and bails
        liveRegion.suspend();        // then clear terminal — no stale write can sneak in
    }

    /** Switch to TURN phase: resume live region, our Display owns the terminal for live rendering. */
    public void enterTurnPhase() {
        phase = Phase.TURN;
        liveRegion.resume();
    }

    // ---- LineReader coordination ------------------------------------------

    /** Call before {@code lineReader.readLine()} — routes scrollback via printAbove. */
    public void setActiveLineReader(LineReader lr) {
        synchronized (this) { activeLineReader = lr; }
    }

    /** Call after {@code readLine()} returns — restores LiveRegion routing. */
    public void clearActiveLineReader() {
        synchronized (this) { activeLineReader = null; }
    }

    // ---- scrollback -------------------------------------------------------

    @Override
    public void scrollback(List<String> lines) {
        if (lines.isEmpty()) return;
        LineReader lr;
        synchronized (this) { lr = activeLineReader; }
        if (lr != null) {
            for (String line : lines) {
                lr.printAbove(line);
            }
            return;
        }
        liveRegion.withLock(() -> {
            if (liveRegion.currentHeight() > 0) {
                liveRegion.commitScrollback(lines);
            } else {
                for (String line : lines) {
                    writer.println(line);
                }
                writer.flush();
            }
        });
    }

    @Override
    public void notifyAsync(List<String> lines) {
        if (lines.isEmpty()) return;
        LineReader lr;
        synchronized (this) { lr = activeLineReader; }
        if (lr != null) {
            for (String line : lines) {
                lr.printAbove(line);
            }
            return;
        }
        scrollback(lines);
    }

    // ---- live region (phase-gated) ----------------------------------------

    @Override
    public synchronized void setLiveStatus(List<String> lines) {
        if (phase == Phase.IDLE) return;
        liveRegion.setStatus(lines);
    }

    @Override
    public synchronized void clearLiveStatus() {
        if (phase == Phase.IDLE) return;
        liveRegion.clearStatus();
    }

    @Override
    public void commitScrollbackAndSetStatus(List<String> scrollbackLines,
                                             List<String> newLiveStatus) {
        if (scrollbackLines.isEmpty() && (newLiveStatus == null || newLiveStatus.isEmpty())) return;
        LineReader lr;
        synchronized (this) { lr = activeLineReader; }
        if (lr != null) {
            for (String line : scrollbackLines) {
                lr.printAbove(line);
            }
            setLiveStatus(newLiveStatus);
            return;
        }
        liveRegion.withLock(() ->
                liveRegion.commitScrollbackAndSetStatus(scrollbackLines, newLiveStatus));
    }

    @Override
    public synchronized void setLiveModal(List<String> lines) {
        if (phase == Phase.IDLE && !composing) return;
        liveRegion.setModal(lines);
    }

    @Override
    public synchronized void clearLiveModal() {
        if (phase == Phase.IDLE && !composing) return;
        liveRegion.clearModal();
    }

    // ---- idle bottom status (JLine Status footer) -------------------------

    /**
     * Pin a status footer to the very bottom of the terminal for the duration
     * of the idle prompt, backed by JLine's {@link Status} (a DECSTBM
     * scroll-region region the {@link LineReader} renders above).
     *
     * <p>Unlike {@link #scrollback}, this region is redrawn in place and fully
     * erased by {@link #clearIdleStatus()} — it never accumulates in history.
     * That is the whole point: the old approach printed the status as a
     * scrollback line each idle cycle, leaving one stale copy per turn.
     *
     * <p>No-op on terminals lacking the required capabilities
     * (change_scroll_region / save_cursor / cursor_address); {@code Status.update}
     * silently degrades there.
     *
     * <p>Must be called while the live region is suspended (IDLE phase): the
     * footer and the TURN-phase live region both own the bottom rows and must
     * never be active at the same time.
     */
    public synchronized void setIdleStatus(List<String> lines) {
        Status status = Status.getStatus(terminal, true);
        if (status == null) return; // not an AbstractTerminal
        if (lines == null || lines.isEmpty()) {
            status.update(Collections.emptyList());
            return;
        }
        List<AttributedString> rows = new ArrayList<>(lines.size());
        for (String line : lines) {
            rows.add(AttributedString.fromAnsi(line));
        }
        status.update(rows);
    }

    /** Remove the bottom status footer and restore the full scroll region. */
    public synchronized void clearIdleStatus() {
        Status status = Status.getStatus(terminal, false);
        if (status != null) {
            status.update(Collections.emptyList());
        }
    }

    // ---- modal locking (used during permission prompts) -------------------

    /** Lock modal channel during inline permission prompts. */
    public void lockModal() {
        liveRegion.lockModal();
    }

    /** Unlock modal channel after inline permission resolves. */
    public void unlockModal() {
        liveRegion.unlockModal();
    }

    // ---- compose phase ----------------------------------------------------

    /**
     * Temporarily allow {@link #setLiveModal} during IDLE phase.
     * Called by {@link madacode.cli.slash.SlashComposer} to display
     * the command palette without a full phase transition.
     */
    public void enterComposePhase() {
        composing = true;
        liveRegion.resume();
    }

    /** End compose phase — re-suspend the live region. */
    public void exitComposePhase() {
        composing = false;
        liveRegion.suspend();
    }

    // ---- resize listener --------------------------------------------------

    /** Register a callback invoked when the terminal is resized. */
    public void setResizeListener(Runnable listener) {
        liveRegion.setResizeListener(listener);
    }

    // ---- terminal queries -------------------------------------------------

    @Override
    public int width() {
        int w = terminal.getWidth();
        return Math.max(20, w == 0 ? 80 : w);
    }

    @Override
    public int height() {
        int h = terminal.getHeight();
        return Math.max(5, h == 0 ? 24 : h);
    }

    @Override
    public synchronized void setCursorVisible(boolean visible) {
        if (activeLineReader != null) return;
        if (visible) {
            if (cursorHideDepth > 0) cursorHideDepth--;
            if (cursorHideDepth == 0) {
                writer.print(CSI + "?25h");
                writer.flush();
            }
        } else {
            if (cursorHideDepth == 0) {
                writer.print(CSI + "?25l");
                writer.flush();
            }
            cursorHideDepth++;
        }
    }

    @Override
    public synchronized void flush() {
        writer.flush();
    }

    @Override
    public synchronized void shutdown() {
        try {
            Status status = Status.getStatus(terminal, false);
            if (status != null) {
                status.update(Collections.emptyList()); // restore full scroll region
            }
            liveRegion.shutdown();
            cursorHideDepth = 0;
            writer.print("\033[0m");
            writer.print(CSI + "?25h");
        } finally {
            writer.flush();
        }
    }
}
