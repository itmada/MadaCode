package madacode.render.turn;

import madacode.plan.CurrentPlan;
import madacode.plan.PlanStep;
import madacode.plan.PlanStepStatus;
import madacode.render.Spinner;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Live plan progress panel pinned to the bottom of the turn's live region.
 *
 * <p>Unlike scrollback stages, a single instance lives across the whole turn:
 * each {@code update_plan} mutates it in place, so the panel redraws rather
 * than reprinting. While a step is {@code in_progress} the panel owns a
 * braille spinner heartbeat (mirroring {@link TurnStatusRenderable}) that asks
 * the {@link TurnView} to repaint so the active row animates. On turn end the
 * panel is finalized and collapses to a one-line summary for scrollback.
 */
public final class PlanPanelRenderable implements Renderable {

    private static final long TICK_MS = 100L;
    private static final ScheduledExecutorService TICK =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plan-panel");
                t.setDaemon(true);
                return t;
            });

    private final Spinner spinner = Spinner.dots();
    private final ScheduledFuture<?> tickFuture;

    private volatile CurrentPlan plan = CurrentPlan.EMPTY;
    private volatile String explanation = "";
    private volatile boolean finalized;
    private volatile boolean marginIssued;

    public PlanPanelRenderable(Runnable repaintCallback) {
        Objects.requireNonNull(repaintCallback, "repaintCallback");
        this.tickFuture = TICK.scheduleAtFixedRate(
                repaintCallback, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Replace the panel content. Called on every {@code update_plan}. */
    public void update(CurrentPlan plan, String explanation) {
        this.plan = plan == null ? CurrentPlan.EMPTY : plan;
        this.explanation = explanation == null ? "" : explanation;
    }

    /** Stop animating and switch {@link #render(int)} to the collapsed summary. */
    public void markFinalized() {
        finalized = true;
        tickFuture.cancel(false);
    }

    @Override
    public List<String> render(int maxWidth) {
        CurrentPlan current = plan;
        if (current == null || current.isEmpty()) {
            return List.of();
        }
        if (finalized) {
            return List.of(PlanPanelFormatter.summary(current));
        }
        String frame = hasInProgress(current) ? spinner.tick() : null;
        return PlanPanelFormatter.live(current, explanation, frame, maxWidth);
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

    private static boolean hasInProgress(CurrentPlan plan) {
        for (PlanStep step : plan.steps()) {
            if (step.status() == PlanStepStatus.IN_PROGRESS) {
                return true;
            }
        }
        return false;
    }
}
