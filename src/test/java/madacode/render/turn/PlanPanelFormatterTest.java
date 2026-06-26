package madacode.render.turn;

import madacode.plan.CurrentPlan;
import madacode.plan.PlanStep;
import madacode.plan.PlanStepStatus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPanelFormatterTest {

    @Test
    void livePanelShowsHeaderBarAndStepRows() {
        CurrentPlan plan = new CurrentPlan(List.of(
                new PlanStep("Read config", PlanStepStatus.COMPLETED),
                new PlanStep("Run the suite", PlanStepStatus.IN_PROGRESS),
                new PlanStep("Update docs", PlanStepStatus.PENDING)));

        List<String> lines = stripAnsi(PlanPanelFormatter.live(plan, "", "⠹", 80));

        assertEquals(List.of(
                "Plan · 1/3  █████░░░░░░░░░░░ 33%",
                " │",
                " │  ✔  Read config",
                " │  ⠹  Run the suite",
                " │  ○  Update docs"), lines);
    }

    @Test
    void liveRendersExplanationAsRailSubtitle() {
        CurrentPlan plan = new CurrentPlan(List.of(
                new PlanStep("Step one", PlanStepStatus.IN_PROGRESS)));

        List<String> lines = stripAnsi(PlanPanelFormatter.live(plan, "wiring auth", "⠋", 80));

        assertEquals(" │  wiring auth", lines.get(1));
    }

    @Test
    void liveActiveGlyphUsesSpinnerFrame() {
        CurrentPlan plan = new CurrentPlan(List.of(
                new PlanStep("Working step", PlanStepStatus.IN_PROGRESS)));

        List<String> a = stripAnsi(PlanPanelFormatter.live(plan, "", "⠙", 80));
        List<String> b = stripAnsi(PlanPanelFormatter.live(plan, "", "⠴", 80));

        assertEquals(" │  ⠙  Working step", a.get(2));
        assertEquals(" │  ⠴  Working step", b.get(2));
    }

    @Test
    void liveTruncatesLongStepToWidth() {
        CurrentPlan plan = new CurrentPlan(List.of(
                new PlanStep("This is a very long step", PlanStepStatus.COMPLETED)));

        List<String> lines = stripAnsi(PlanPanelFormatter.live(plan, "", null, 20));

        assertEquals(" │  ✔  This is a ve…", lines.get(2));
    }

    @Test
    void emptyPlanRendersNoLiveLines() {
        assertTrue(PlanPanelFormatter.live(CurrentPlan.EMPTY, "", "⠋", 80).isEmpty());
    }

    @Test
    void summaryWithAllDoneShowsCheck() {
        CurrentPlan plan = new CurrentPlan(List.of(
                new PlanStep("A", PlanStepStatus.COMPLETED),
                new PlanStep("B", PlanStepStatus.COMPLETED)));

        assertEquals("✔ Plan · 2/2 done", strip(PlanPanelFormatter.summary(plan)));
    }

    @Test
    void summaryWithPendingOmitsCheck() {
        CurrentPlan plan = new CurrentPlan(List.of(
                new PlanStep("A", PlanStepStatus.COMPLETED),
                new PlanStep("B", PlanStepStatus.PENDING)));

        assertEquals("Plan · 1/2 done", strip(PlanPanelFormatter.summary(plan)));
    }

    @Test
    void summaryEmptyPlanIsBlank() {
        assertEquals("", PlanPanelFormatter.summary(CurrentPlan.EMPTY));
    }

    private static List<String> stripAnsi(List<String> lines) {
        return lines.stream().map(PlanPanelFormatterTest::strip).toList();
    }

    private static String strip(String s) {
        return s.replaceAll("\\e\\[[;\\d]*m", "");
    }
}
