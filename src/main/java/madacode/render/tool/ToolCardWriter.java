package madacode.render.tool;

import madacode.render.ExpandableHistory;
import madacode.render.BlockSpacing;
import madacode.render.StageWriter;
import madacode.tui.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-phase tool card renderer for scrollback.
 *
 * <p>{@link #writeStart} renders the header when a {@code ToolUseBlock} arrives;
 * {@link #writeResult} appends result lines when the matching
 * {@code ToolResultBlock} arrives. {@link #write} renders a complete card in
 * one shot for callers that don't use two-phase rendering.
 */
public final class ToolCardWriter {

    private ToolCardWriter() {}

    /** Render the start card header when the tool call first appears. */
    public static void writeStart(Screen screen, ToolDisplay display) {
        StageWriter.Stage stage = new StageWriter.Stage(
                ToolActivityCardRenderer.stage(display, false).status(),
                display.title(),
                List.of(),
                List.of(),
                false);
        BlockSpacing.scrollbackBlock(screen, StageWriter.render(stage));
    }

    /**
     * Append result lines below a previously rendered start card.
     *
     * <p>Skips the header line (already in scrollback from {@link #writeStart}),
     * then appends result timeline lines.
     */
    public static void writeResult(Screen screen, ToolDisplay display,
                                    long durationMs,
                                    ExpandableHistory expandableHistory) {
        boolean expandable = expandableHistory != null
                && ToolActivityCardRenderer.hiddenLineCount(display) > 0;
        StageWriter.Stage stage = ToolActivityCardRenderer.stage(display, expandable);

        List<String> fullCard = StageWriter.render(stage);
        List<String> lines = new ArrayList<>();
        if (fullCard.size() > 1) {
            lines.addAll(fullCard.subList(1, fullCard.size()));
        }

        screen.scrollback(lines);
        if (expandable) {
            expandableHistory.set(stage);
        }
    }

    /** Commit a complete tool card to scrollback in one shot. */
    public static void write(Screen screen, ToolDisplay display) {
        write(screen, display, null);
    }

    public static void write(Screen screen, ToolDisplay display,
                              ExpandableHistory expandableHistory) {
        boolean expandable = expandableHistory != null
                && ToolActivityCardRenderer.hiddenLineCount(display) > 0;
        StageWriter.Stage stage = ToolActivityCardRenderer.stage(display, expandable);
        if (expandable) {
            expandableHistory.set(stage);
        }
        BlockSpacing.scrollbackBlock(screen, StageWriter.render(stage));
    }
}
