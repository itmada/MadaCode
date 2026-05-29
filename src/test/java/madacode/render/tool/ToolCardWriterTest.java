package madacode.render.tool;

import madacode.render.ExpandableHistory;
import madacode.tui.Screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCardWriterTest {

    @Test
    void writeOutputContainsToolTitleAndSummary() {
        CapturingScreen screen = new CapturingScreen();
        ToolDisplay display = ToolDisplay.success(
                "Bash(npm test)", "Completed · 3.8s", List.of("42 tests passed"));

        ToolCardWriter.write(screen, display);

        assertFalse(screen.scrollbackLines.isEmpty());
        assertEquals("", screen.scrollbackLines.getFirst(),
                "tool card should own a leading blank line");
        assertFalse(screen.scrollbackLines.getLast().isEmpty(),
                "tool card should not rely on trailing blank spacing");
        String output = strip(join(screen.scrollbackLines));
        assertTrue(output.contains("Bash(npm test)"), "should contain title: " + output);
        assertTrue(output.contains("Completed"), "should contain summary: " + output);
        assertTrue(output.contains("42 tests passed"), "should contain detail: " + output);
    }

    @Test
    void expandableDisplayShowsCtrlOToExpandHint() {
        CapturingScreen screen = new CapturingScreen();
        ExpandableHistory history = new ExpandableHistory();
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("shown"),
                List.of("shown", "hidden1", "hidden2"),
                DisplayStatus.SUCCESS);

        ToolCardWriter.write(screen, display, history);

        String output = strip(join(screen.scrollbackLines));
        assertTrue(output.contains("ctrl+o to expand"),
                "should show expand hint: " + output);
        assertTrue(output.contains("2 lines hidden"),
                "should show hidden count: " + output);
    }

    @Test
    void hiddenVerboseLinesNotInScrollbackByDefault() {
        CapturingScreen screen = new CapturingScreen();
        ExpandableHistory history = new ExpandableHistory();
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("shown"),
                List.of("shown", "hidden1", "hidden2"),
                DisplayStatus.SUCCESS);

        ToolCardWriter.write(screen, display, history);

        String output = strip(join(screen.scrollbackLines));
        assertTrue(output.contains("shown"));
        assertFalse(output.contains("hidden1"),
                "hidden detail should not appear in scrollback: " + output);
        assertFalse(output.contains("hidden2"));
    }

    @Test
    void expandableHistoryRevealsHiddenLines() {
        CapturingScreen screen = new CapturingScreen();
        ExpandableHistory history = new ExpandableHistory();
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("shown"),
                List.of("shown", "hidden1", "hidden2"),
                DisplayStatus.SUCCESS);

        ToolCardWriter.write(screen, display, history);
        history.expandInto(screen);

        String expanded = strip(join(screen.scrollbackLines));
        assertTrue(expanded.contains("hidden1"),
                "expand should reveal hidden details: " + expanded);
        assertTrue(expanded.contains("hidden2"));
    }

    @Test
    void noExpandHintWhenVerboseEqualsDetails() {
        CapturingScreen screen = new CapturingScreen();
        ExpandableHistory history = new ExpandableHistory();
        ToolDisplay display = ToolDisplay.success(
                "Bash(test)", "Done", List.of("a", "b"));

        ToolCardWriter.write(screen, display, history);

        String output = strip(join(screen.scrollbackLines));
        assertFalse(output.contains("ctrl+o to expand"),
                "should not show expand hint when no hidden lines: " + output);
    }

    @Test
    void writeWithoutExpandableHistoryDoesNotAdvertiseExpand() {
        CapturingScreen screen = new CapturingScreen();
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("a"),
                List.of("a", "hidden"),
                DisplayStatus.SUCCESS);

        ToolCardWriter.write(screen, display); // no expandableHistory

        String output = strip(join(screen.scrollbackLines));
        assertFalse(output.contains("ctrl+o to expand"),
                "should not show expand hint without expandableHistory");
    }

    @Test
    void deniedStatusHasCorrectBullet() {
        String bullet = ToolActivityCardRenderer.statusBullet(DisplayStatus.DENIED);
        String plain = strip(bullet);
        assertTrue(plain.contains("✣"), "denied bullet should be ✣: " + plain);
    }

    // ---- helpers -------------------------------------------------------

    private static final class CapturingScreen implements Screen {
        final List<String> scrollbackLines = new ArrayList<>();

        @Override public synchronized void scrollback(List<String> lines) {
            scrollbackLines.addAll(lines);
        }

        @Override public int width() { return 80; }
        @Override public int height() { return 24; }
        @Override public void flush() {}
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String strip(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
