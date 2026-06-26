package madacode.render.turn;

import madacode.plan.CurrentPlan;
import madacode.plan.PlanStep;
import madacode.plan.PlanStepStatus;
import madacode.tui.TerminalText;
import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure layout for the live plan panel and its collapsed history summary.
 *
 * <p>Separated from {@link PlanPanelRenderable} so the visual rules are a
 * pure function of {@link CurrentPlan} (plus the current spinner frame) and
 * can be unit-tested without any live-region or scheduling machinery.
 */
final class PlanPanelFormatter {

    private static final int BAR_WIDTH = 16;
    /** Display columns occupied by " │  " + glyph + "  " before the step text. */
    private static final int STEP_PREFIX_COLS = 7;
    private static final String RAIL = "│";
    private static final String GLYPH_DONE = "✔";
    private static final String GLYPH_PENDING = "○";
    private static final String GLYPH_ACTIVE_STATIC = "⠧";

    private PlanPanelFormatter() {}

    /** The live, in-place panel. Empty plan renders nothing. */
    static List<String> live(CurrentPlan plan, String explanation, String spinnerFrame, int maxWidth) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<PlanStep> steps = plan.steps();
        int total = steps.size();
        int completed = completedCount(steps);

        List<String> lines = new ArrayList<>(total + 2);
        lines.add(header(completed, total));
        lines.add(subtitle(explanation));

        int available = Math.max(1, maxWidth - STEP_PREFIX_COLS);
        for (PlanStep step : steps) {
            lines.add(stepRow(step, spinnerFrame, available));
        }
        return lines;
    }

    /** One-line summary spilled to scrollback when the turn ends. Empty plan → "". */
    static String summary(CurrentPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        int total = plan.steps().size();
        int completed = completedCount(plan.steps());
        String count = Tk.dim(" · " + completed + "/" + total + " done");
        return completed == total
                ? Tk.success(GLYPH_DONE) + " " + Tk.bold("Plan") + count
                : Tk.bold("Plan") + count;
    }

    private static String header(int completed, int total) {
        int pct = (int) Math.round(100.0 * completed / total);
        int filled = (int) Math.round((double) BAR_WIDTH * completed / total);
        filled = Math.max(0, Math.min(BAR_WIDTH, filled));
        String bar = Tk.success("█".repeat(filled)) + Tk.dim("░".repeat(BAR_WIDTH - filled));
        return Tk.bold("Plan")
                + Tk.dim(" · " + completed + "/" + total)
                + "  " + bar
                + Tk.dim(" " + pct + "%");
    }

    private static String subtitle(String explanation) {
        String rail = " " + Tk.dim(RAIL);
        if (explanation == null || explanation.isBlank()) {
            return rail;
        }
        return rail + "  " + Tk.dim(explanation.strip());
    }

    private static String stepRow(PlanStep step, String spinnerFrame, int available) {
        String text = TerminalText.fitEnd(step.step(), available);
        String glyph;
        String styledText;
        switch (step.status()) {
            case COMPLETED -> {
                glyph = Tk.success(GLYPH_DONE);
                styledText = Tk.dim(text);
            }
            case IN_PROGRESS -> {
                String frame = (spinnerFrame == null || spinnerFrame.isBlank())
                        ? GLYPH_ACTIVE_STATIC : spinnerFrame;
                glyph = Tk.running(frame);
                styledText = Tk.bold(text);
            }
            default -> {
                glyph = Tk.dim(GLYPH_PENDING);
                styledText = Tk.dim(text);
            }
        }
        return " " + Tk.dim(RAIL) + "  " + glyph + "  " + styledText;
    }

    private static int completedCount(List<PlanStep> steps) {
        int n = 0;
        for (PlanStep step : steps) {
            if (step.status() == PlanStepStatus.COMPLETED) {
                n++;
            }
        }
        return n;
    }
}
