package madacode.render.turn;

import madacode.plan.CurrentPlan;
import madacode.plan.PlanStep;
import madacode.plan.PlanStepStatus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPanelRenderableTest {

    private static final Runnable NOOP = () -> {};

    @Test
    void rendersNothingBeforeFirstUpdate() {
        PlanPanelRenderable panel = new PlanPanelRenderable(NOOP);
        assertTrue(panel.render(80).isEmpty());
        assertFalse(panel.isFinalized());
        panel.markFinalized();
    }

    @Test
    void rendersLivePanelAfterUpdate() {
        PlanPanelRenderable panel = new PlanPanelRenderable(NOOP);
        panel.update(planWithActive(), "");

        List<String> lines = panel.render(80);

        assertEquals(5, lines.size());
        assertTrue(strip(lines.get(0)).startsWith("Plan · 1/3"));
        panel.markFinalized();
    }

    @Test
    void activeStepSpinnerAdvancesBetweenRenders() {
        PlanPanelRenderable panel = new PlanPanelRenderable(NOOP);
        panel.update(planWithActive(), "");

        String first = activeGlyph(panel.render(80));
        String second = activeGlyph(panel.render(80));

        assertNotEquals(first, second);
        panel.markFinalized();
    }

    @Test
    void finalizeCollapsesToSummaryLine() {
        PlanPanelRenderable panel = new PlanPanelRenderable(NOOP);
        panel.update(planWithActive(), "");

        panel.markFinalized();

        assertEquals(List.of("Plan · 1/3 done"), strip(panel.render(80)));
        assertTrue(panel.isFinalized());
    }

    @Test
    void heartbeatRequestsRepaint() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        PlanPanelRenderable panel = new PlanPanelRenderable(latch::countDown);
        panel.update(planWithActive(), "");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "expected heartbeat to request a repaint");
        panel.markFinalized();
    }

    private static CurrentPlan planWithActive() {
        return new CurrentPlan(List.of(
                new PlanStep("done step", PlanStepStatus.COMPLETED),
                new PlanStep("active step", PlanStepStatus.IN_PROGRESS),
                new PlanStep("todo step", PlanStepStatus.PENDING)));
    }

    private static String activeGlyph(List<String> lines) {
        for (String line : lines) {
            String s = strip(line);
            if (s.contains("active step")) {
                return s.substring(4, 5);
            }
        }
        return "";
    }

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(PlanPanelRenderableTest::strip).toList();
    }

    private static String strip(String s) {
        return s.replaceAll("\\e\\[[;\\d]*m", "");
    }
}
