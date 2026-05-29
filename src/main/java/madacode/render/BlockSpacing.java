package madacode.render;

import madacode.tui.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared spacing policy for terminal content blocks.
 *
 * <p>Each visible block owns a top margin. Scrollback blocks and live
 * activity rows both enter through this helper so spacing does not depend
 * on whichever renderer happened to run immediately before them.
 */
public final class BlockSpacing {

    private BlockSpacing() {}

    public static void begin(Screen screen) {
        Objects.requireNonNull(screen, "screen").scrollback("");
    }

    public static void scrollbackBlock(Screen screen, String line) {
        scrollbackBlock(screen, List.of(line));
    }

    public static void scrollbackBlock(Screen screen, List<String> lines) {
        Objects.requireNonNull(screen, "screen").scrollback(withLeadingBlank(lines));
    }

    public static List<String> activityBlock(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        return List.copyOf(lines);
    }

    private static List<String> withLeadingBlank(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            return List.of();
        }
        if (lines.getFirst().isEmpty()) {
            return List.copyOf(lines);
        }
        List<String> spaced = new ArrayList<>(lines.size() + 1);
        spaced.add("");
        spaced.addAll(lines);
        return List.copyOf(spaced);
    }
}
