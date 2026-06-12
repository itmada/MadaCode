package madacode.render.turn;

import madacode.tui.theme.Tk;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A transient spinner shown while waiting for the model's first response.
 * Uses a shared daemon scheduler to drive periodic repaints.
 * Once finalized it renders as empty — the TurnView will stop showing it.
 */
public final class ThinkingRenderable implements Renderable {

    private static final String[] FRAMES = {"✦", "✧"};
    private static final long FRAME_NS = 500_000_000L;

    private static final ScheduledExecutorService TICK =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "thinking-spinner");
                t.setDaemon(true);
                return t;
            });

    private final long startNs = System.nanoTime();
    private final ScheduledFuture<?> tickFuture;
    private volatile boolean finalized;
    private volatile boolean marginIssued;

    public ThinkingRenderable(Runnable repaintCallback) {
        this.tickFuture = TICK.scheduleAtFixedRate(
                repaintCallback, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void finalizeThinking() {
        finalized = true;
        tickFuture.cancel(false);
    }

    @Override
    public List<String> render(int maxWidth) {
        if (finalized) return List.of();
        int idx = (int) (((System.nanoTime() - startNs) / FRAME_NS) % FRAMES.length);
        return List.of(Tk.thinking(FRAMES[idx]) + " " + Tk.dim("Thinking…"));
    }

    @Override
    public boolean isFinalized() {
        return finalized;
    }

    @Override
    public boolean isMarginIssued() { return marginIssued; }

    @Override
    public void markMarginIssued() { marginIssued = true; }
}
