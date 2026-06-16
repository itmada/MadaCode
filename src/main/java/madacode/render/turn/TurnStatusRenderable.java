package madacode.render.turn;

import madacode.render.Spinner;
import madacode.tui.TerminalText;
import madacode.tui.theme.Tk;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class TurnStatusRenderable implements Renderable {

    public enum Mode {
        /** Model is thinking/generating — status line owns an animated star pulse. */
        THINKING,
        /**
         * Tools are queued or awaiting permission but none is animating yet
         * (the card is still pure-queued and renders empty). The status line is
         * the sole "busy" indicator, so it owns the animated braille spinner and
         * keeps the UI from going blank across the permission/hook gap.
         */
        WORKING,
        /**
         * A tool card is actively animating its own spinner. The status line
         * yields the animation to the card and degrades to a quiet, static
         * turn-level line (elapsed + interrupt hint), so the screen never shows
         * two braille spinners at once.
         */
        TOOL_ACTIVE
    }

    private static final long TICK_MS = 120L;
    private static final long ELAPSED_AFTER_MS = 5_000L;
    private static final ScheduledExecutorService TICK =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "turn-status");
                t.setDaemon(true);
                return t;
            });

    private final long startMs = System.currentTimeMillis();
    private final Spinner spinner = Spinner.dots();
    private final Spinner thinkingSpinner = Spinner.thinking();
    private final ScheduledFuture<?> tickFuture;
    private volatile String message;
    private volatile Mode mode;
    private volatile boolean finalized;
    private volatile boolean marginIssued;

    public TurnStatusRenderable(String message, Mode mode, Runnable repaintCallback) {
        this.message = message == null ? "" : message;
        this.mode = mode == null ? Mode.WORKING : mode;
        this.tickFuture = TICK.scheduleAtFixedRate(
                repaintCallback, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public void updateMessage(String message) {
        updateMessage(message, mode);
    }

    public void updateMessage(String message, Mode mode) {
        this.message = message == null ? "" : message;
        this.mode = mode == null ? Mode.WORKING : mode;
    }

    public void finalizeStatus() {
        finalized = true;
        tickFuture.cancel(false);
    }

    @Override
    public List<String> render(int maxWidth) {
        if (finalized) {
            return List.of();
        }
        // TOOL_ACTIVE yields the animation to the active tool card and stays
        // static; THINKING and WORKING own the only on-screen spinner.
        String styledGlyph = switch (mode) {
            case THINKING -> Tk.thinking(thinkingSpinner.tick());
            case WORKING -> Tk.running(spinner.tick());
            case TOOL_ACTIVE -> Tk.dim("·");
        };
        String text = styledGlyph + " " + Tk.dim(decorateMessage());
        return List.of(TerminalText.fitEnd(text, Math.max(0, maxWidth)));
    }

    private String decorateMessage() {
        String current;
        if (mode == Mode.TOOL_ACTIVE) {
            // The active tool card already shows the per-tool detail; the status
            // line only carries the turn-level elapsed + interrupt affordance.
            current = "Working…";
        } else {
            current = message == null ? "" : message.strip();
            if (current.isBlank()) {
                current = mode == Mode.THINKING ? "Thinking..." : "Working...";
            }
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startMs);
        if (elapsedMs < ELAPSED_AFTER_MS) {
            return current;
        }
        long elapsedSeconds = Math.max(1L, elapsedMs / 1000L);
        return current + " (" + elapsedSeconds + "s · esc to interrupt)";
    }

    @Override
    public boolean isFinalized() {
        return finalized;
    }

    @Override
    public boolean isMarginIssued() {
        return marginIssued;
    }

    @Override
    public void markMarginIssued() {
        marginIssued = true;
    }
}
