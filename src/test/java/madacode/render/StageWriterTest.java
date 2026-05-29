package madacode.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageWriterTest {

    @Test
    void rendersEveryStatusWithStageBullet() {
        for (StageWriter.Status status : StageWriter.Status.values()) {
            List<String> lines = StageWriter.render(new StageWriter.Stage(
                    status, "Title", List.of("summary"), List.of(), false));

            String plain = strip(String.join("\n", lines));
            assertTrue(plain.startsWith("✣ Title"), status + " title: " + plain);
            assertTrue(plain.contains("⎿ summary"), status + " summary: " + plain);
        }
    }

    @Test
    void summaryLinesUseAlignedContinuationIndent() {
        List<String> lines = StageWriter.render(new StageWriter.Stage(
                StageWriter.Status.SUCCESS,
                "Task",
                List.of("first", "second"),
                List.of(),
                false));

        assertEquals("✣ Task", strip(lines.get(0)));
        assertEquals("  ⎿ first", strip(lines.get(1)));
        assertEquals("     second", strip(lines.get(2)));
    }

    @Test
    void hasMoreShowsCtrlOHintWithHiddenCount() {
        List<String> lines = StageWriter.render(new StageWriter.Stage(
                StageWriter.Status.SUCCESS,
                "Bash",
                List.of("shown"),
                List.of("hidden-1", "hidden-2"),
                true));

        String plain = strip(String.join("\n", lines));
        assertTrue(plain.contains("(ctrl+o to expand · 2 lines hidden)"), plain);
    }

    @Test
    void hasMoreWithoutVerboseDoesNotShowHint() {
        List<String> lines = StageWriter.render(new StageWriter.Stage(
                StageWriter.Status.SUCCESS,
                "Bash",
                List.of("shown"),
                List.of(),
                true));

        assertTrue(!strip(String.join("\n", lines)).contains("ctrl+o"));
    }

    @Test
    void verboseLinesUseStageIndent() {
        List<String> lines = StageWriter.renderVerbose(new StageWriter.Stage(
                StageWriter.Status.SUCCESS,
                "Bash",
                List.of("shown"),
                List.of("hidden-1", "hidden-2"),
                true));

        assertEquals("  ⎿ hidden-1", strip(lines.get(0)));
        assertEquals("     hidden-2", strip(lines.get(1)));
    }

    @Test
    void plainTextContainsNoAnsiAfterStripping() {
        String plain = strip(String.join("\n", StageWriter.render(new StageWriter.Stage(
                StageWriter.Status.FAILED,
                "Error",
                List.of("broken"),
                List.of(),
                false))));

        assertEquals("✣ Error\n  ⎿ broken", plain);
    }

    private static String strip(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
