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
        IDLE,
        THINKING,
        REQUESTING,
        TOOL_USE
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
    private final ScheduledFuture<?> tickFuture;
    private volatile String message;
    private volatile Mode mode;
    private volatile boolean finalized;
    private volatile boolean marginIssued;

    public TurnStatusRenderable(String message, Runnable repaintCallback) {
        this(message, Mode.IDLE, repaintCallback);
    }

    public TurnStatusRenderable(String message, Mode mode, Runnable repaintCallback) {
        this.message = message == null ? "" : message;
        this.mode = mode == null ? Mode.IDLE : mode;
        this.tickFuture = TICK.scheduleAtFixedRate(
                repaintCallback, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public void updateMessage(String message) {
        updateMessage(message, mode);
    }

    public void updateMessage(String message, Mode mode) {
        this.message = message == null ? "" : message;
        this.mode = mode == null ? Mode.IDLE : mode;
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
        String text = spinner.tick() + " " + decorateMessage();
        return List.of(Tk.dim(TerminalText.fitEnd(text, Math.max(0, maxWidth))));
    }

    private String decorateMessage() {
        String current = message == null ? "" : message.strip();
        if (current.isBlank()) {
            current = switch (mode) {
                case THINKING -> "Thinking...";
                case REQUESTING -> "Requesting...";
                case TOOL_USE -> "Working...";
                case IDLE -> "";
            };
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startMs);
        if (elapsedMs < ELAPSED_AFTER_MS) {
            return current;
        }
        long elapsedSeconds = Math.max(1L, elapsedMs / 1000L);
        return current + " (" + elapsedSeconds + "s)";
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
