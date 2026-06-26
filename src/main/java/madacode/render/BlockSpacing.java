package madacode.render;

import madacode.tui.Screen;

import java.util.List;
import java.util.Objects;

/**
 * Shared spacing policy for terminal content blocks.
 *
 * <p>Each visible block owns its bottom margin. Scrollback blocks and live
 * activity rows both enter through this helper so spacing does not depend
 * on whichever renderer happened to run immediately before them.
 */
public final class BlockSpacing {

    private BlockSpacing() {}

    public static void begin(Screen screen) {
        Objects.requireNonNull(screen, "screen").ensureScrollbackBoundary();
    }

    public static void scrollbackBlock(Screen screen, String line) {
        scrollbackBlock(screen, List.of(line));
    }

    public static void scrollbackBlock(Screen screen, List<String> lines) {
        Screen target = Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            return;
        }
        target.ensureScrollbackBoundary();
        target.scrollback(lines);
        target.ensureScrollbackBoundary();
    }

    public static List<String> activityBlock(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        return List.copyOf(lines);
    }

}
