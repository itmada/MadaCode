package madacode.render;

import static madacode.tui.theme.Tk.dim;
import static madacode.tui.theme.Tk.promptHistory;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared scrollback rendering for user input.
 *
 * <p>Matches the live editor shape: first line owns the active prompt marker,
 * continuation lines are visually indented without an extra marker.
 */
public final class UserInputRenderer {

    private UserInputRenderer() {}

    public static List<String> lines(String text) {
        List<String> rendered = new ArrayList<>();
        int index = 0;
        for (String line : (Iterable<String>) text.lines()::iterator) {
            if (index == 0) {
                rendered.add(promptHistory("❯") + " " + dim(line));
            } else {
                rendered.add("  " + dim(line));
            }
            index++;
        }
        return rendered;
    }
}
