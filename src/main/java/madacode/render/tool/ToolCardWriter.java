package madacode.render.tool;

import madacode.render.ExpandableHistory;
import madacode.render.StageWriter;
import madacode.tui.Screen;

import java.util.List;

/**
 * Complete tool card renderer for scrollback history.
 */
public final class ToolCardWriter {

    private ToolCardWriter() {}

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
        screen.commitBlock(StageWriter.render(stage));
    }
}
