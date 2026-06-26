package madacode.render.turn;

import madacode.core.model.MetaEvent;
import madacode.plan.CurrentPlan;
import madacode.plan.PlanStep;
import madacode.plan.PlanStepStatus;
import madacode.tui.Screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnRendererPlanTest {

    @Test
    void planUpdatesRenderLiveAndCollapseToOneSummaryLine() {
        CaptureScreen screen = new CaptureScreen();
        TurnView view = new TurnView(screen);
        TurnRenderer renderer = new TurnRenderer(view, screen);

        renderer.onMetaEvent(new MetaEvent.PlanUpdated(
                plan(PlanStepStatus.IN_PROGRESS, PlanStepStatus.PENDING), ""));
        view.flushNow();
        assertTrue(hasLine(screen.live, "Plan · 0/2"), "live region should show the plan panel");
        assertTrue(screen.scrollback.isEmpty(), "panel must not write scrollback during the turn");

        // Second update mutates the same panel in place — nothing reprinted.
        renderer.onMetaEvent(new MetaEvent.PlanUpdated(
                plan(PlanStepStatus.COMPLETED, PlanStepStatus.IN_PROGRESS), ""));
        view.flushNow();
        assertTrue(hasLine(screen.live, "Plan · 1/2"), "live region should show the updated plan");
        assertTrue(screen.scrollback.isEmpty(), "in-place updates must not spam scrollback");

        renderer.onTurnEnd();

        List<String> sb = strip(screen.scrollback);
        assertEquals(1, sb.stream().filter(line -> line.contains("Plan ·")).count(),
                "exactly one collapsed summary line lands in history");
        assertTrue(sb.contains("Plan · 1/2 done"), "summary reflects final progress");
        assertEquals(List.of(), screen.live, "panel is cleared from the live region at turn end");
    }

    private static CurrentPlan plan(PlanStepStatus first, PlanStepStatus second) {
        return new CurrentPlan(List.of(
                new PlanStep("first step", first),
                new PlanStep("second step", second)));
    }

    private static boolean hasLine(List<String> lines, String needle) {
        return strip(lines).stream().anyMatch(line -> line.contains(needle));
    }

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(s -> s.replaceAll("\\e\\[[;\\d]*m", "")).toList();
    }

    private static final class CaptureScreen implements Screen {
        final List<String> scrollback = new ArrayList<>();
        volatile List<String> live = List.of();

        @Override
        public synchronized void scrollback(List<String> lines) {
            scrollback.addAll(lines);
        }

        @Override
        public synchronized void setLiveStatus(List<String> lines) {
            live = List.copyOf(lines);
        }

        @Override
        public synchronized void commitScrollbackAndSetStatus(
                List<String> scrollbackLines, List<String> newLiveStatus) {
            scrollback.addAll(scrollbackLines);
            live = List.copyOf(newLiveStatus);
        }

        @Override public int width() { return 80; }
        @Override public int height() { return 24; }
        @Override public void flush() {}
    }
}
