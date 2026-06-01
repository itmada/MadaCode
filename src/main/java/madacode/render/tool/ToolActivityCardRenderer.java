package madacode.render.tool;

import madacode.render.StageWriter;
import madacode.tui.TerminalText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static madacode.tui.theme.Tk.*;

/**
 * Unified renderer for tool-activity cards shown in both the live drawer
 * (via {@link madacode.tui.widget.ToolRow}) and scrollback history
 * (via {@link ToolCardWriter} / {@link StageWriter}).
 *
 * <p>Single source of truth for stage assembly, summary extraction, and
 * status-to-glyph/bullet mapping.
 */
public final class ToolActivityCardRenderer {

    private ToolActivityCardRenderer() {}

    // ---- card rendering -----------------------------------------------

    /** Render a full tool card (no trailing blank). */
    public static List<String> card(ToolDisplay display) {
        return card(display, Integer.MAX_VALUE);
    }

    /** Render a full tool card whose every line fits within {@code maxWidth}. */
    public static List<String> card(ToolDisplay display, int maxWidth) {
        Objects.requireNonNull(display, "display");
        StageWriter.Stage stage = stage(display, false);
        return clampWidth(StageWriter.render(stage), maxWidth);
    }

    // ---- Stage assembly (used by ToolCardWriter) ----------------------

    public static StageWriter.Stage stage(ToolDisplay display, boolean expandable) {
        return new StageWriter.Stage(
                stageStatus(display.status()),
                display.title(),
                visibleSummary(display),
                expandable ? verboseTail(display) : List.of(),
                expandable && hiddenLineCount(display) > 0);
    }

    /** Lines visible by default: summary (if non-blank) + detailLines. */
    public static List<String> visibleSummary(ToolDisplay display) {
        if (display.summary().isBlank()) {
            return display.detailLines();
        }
        List<String> out = new ArrayList<>();
        out.add(display.summary());
        out.addAll(display.detailLines());
        return out;
    }

    /** Verbose detail lines that sit behind the expand toggle. */
    public static List<String> verboseTail(ToolDisplay display) {
        int cut = display.detailLines().size();
        if (cut >= display.verboseDetailLines().size()) {
            return List.of();
        }
        return display.verboseDetailLines()
                .subList(cut, display.verboseDetailLines().size());
    }

    public static int hiddenLineCount(ToolDisplay display) {
        return Math.max(0,
                display.verboseDetailLines().size() - display.detailLines().size());
    }

    // ---- live-drawer helpers (used by ToolRow) -----------------------

    /** Running-state summary with elapsed seconds. */
    public static String runningSummary(ToolDisplay display, long elapsedSec) {
        String summary = display.summary().isBlank() ? "Running" : display.summary();
        return summary + " · " + elapsedSec + "s";
    }

    /** Plain glyph character by status. */
    public static String statusGlyph(DisplayStatus status) {
        return switch (status) {
            case RUNNING -> "●";
            case SUCCESS -> "●";
            case FAILED, DENIED -> "●";
            case INFO -> "●";
        };
    }

    /** Styled bullet (with theme colour) for the card header / live row. */
    public static String statusBullet(DisplayStatus status) {
        return switch (status) {
            case RUNNING -> running("●");
            case SUCCESS -> success("●");
            case FAILED  -> failure("●");
            case DENIED  -> failure("●");
            case INFO    -> dim("●");
        };
    }

    // ---- internal -----------------------------------------------------

    private static StageWriter.Status stageStatus(DisplayStatus status) {
        return switch (status) {
            case RUNNING -> StageWriter.Status.RUNNING;
            case SUCCESS -> StageWriter.Status.SUCCESS;
            case FAILED -> StageWriter.Status.FAILED;
            case DENIED -> StageWriter.Status.DENIED;
            case INFO -> StageWriter.Status.INFO;
        };
    }

    private static List<String> clampWidth(List<String> lines, int maxWidth) {
        int safeWidth = Math.max(1, maxWidth);
        for (int i = 0; i < lines.size(); i++) {
            if (TerminalText.displayWidth(lines.get(i)) > safeWidth) {
                lines.set(i, TerminalText.fitEnd(lines.get(i), safeWidth));
            }
        }
        return lines;
    }
}
