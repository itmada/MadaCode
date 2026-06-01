package madacode.render;

import madacode.render.tool.DisplayStatus;
import madacode.render.tool.ToolActivityCardRenderer;
import madacode.render.tool.ToolDisplay;
import madacode.tui.TerminalText;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolActivityCardRendererTest {

    @Test
    void bashRunningCard() {
        ToolDisplay display = ToolDisplay.running("Bash(npm test)", "running");
        List<String> lines = ToolActivityCardRenderer.card(display);

        assertFalse(lines.isEmpty());
        String output = join(lines);
        assertTrue(strip(output).contains("Bash    npm test"), "should contain tool name: " + output);
        assertTrue(strip(output).contains("running"), "should contain summary: " + output);
    }

    @Test
    void bashSuccessCard() {
        ToolDisplay display = ToolDisplay.success(
                "Bash(npm test)", "Completed · 3.8s", List.of("42 tests passed"));

        List<String> lines = ToolActivityCardRenderer.card(display);
        String output = join(lines);

        assertTrue(strip(output).contains("Bash    npm test"));
        assertTrue(strip(output).contains("Completed") && strip(output).contains("3.8s"),
                "should show elapsed: " + output);
        assertTrue(strip(output).contains("42 tests passed"),
                "should show detail line: " + output);
    }

    @Test
    void failureCard() {
        ToolDisplay display = ToolDisplay.failed(
                "Bash(rm -rf /)", "Failed · 820ms",
                List.of("Permission denied"));

        List<String> lines = ToolActivityCardRenderer.card(display);
        String output = join(lines);

        assertTrue(strip(output).contains("Bash    rm -rf /"));
        assertTrue(strip(output).contains("Failed"), "should show Failed: " + output);
        assertTrue(strip(output).contains("Permission denied"),
                "should show error detail: " + output);
    }

    @Test
    void deniedCard() {
        ToolDisplay display = new ToolDisplay(
                "Bash(rm -rf /)",
                "Permission denied",
                List.of("User denied permission for tool: bash"),
                DisplayStatus.DENIED);

        List<String> lines = ToolActivityCardRenderer.card(display);
        String output = join(lines);

        assertTrue(strip(output).contains("Bash    rm -rf /"),
                "should contain tool name");
        assertTrue(strip(output).contains("Permission denied"),
                "should contain denied summary: " + output);
    }

    @Test
    void fileWriteCardContainsPath() {
        ToolDisplay display = ToolDisplay.success(
                "Write(src/main/java/App.java)", "Wrote 124 lines", List.of());

        List<String> lines = ToolActivityCardRenderer.card(display);
        String output = join(lines);

        assertTrue(strip(output).contains("Write   src/main/java/App.java"),
                "should contain file path: " + output);
        assertTrue(strip(output).contains("Wrote 124 lines"),
                "should contain line count: " + output);
    }

    @Test
    void webFetchCardContainsUrl() {
        ToolDisplay display = ToolDisplay.success(
                "Fetch(https://example.com)", "HTTP 200 · 18 KB · 1.2s", List.of());

        List<String> lines = ToolActivityCardRenderer.card(display);
        String output = join(lines);

        assertTrue(strip(output).contains("Fetch   https://example.com"),
                "should contain URL: " + output);
        assertTrue(strip(output).contains("200") && strip(output).contains("18 KB"),
                "should contain HTTP status and size: " + output);
    }

    @Test
    void longCommandIsTruncated() {
        String longCmd = "echo " + "x".repeat(120);
        ToolDisplay display = ToolDisplay.success(
                "Bash(" + longCmd + ")", "Done", List.of());

        List<String> lines = ToolActivityCardRenderer.card(display, 80);
        for (int i = 0; i < lines.size(); i++) {
            int dw = TerminalText.displayWidth(lines.get(i));
            assertTrue(dw <= 80,
                    "line " + i + " width " + dw + " exceeds 80: " + lines.get(i));
        }
    }

    @Test
    void narrowWidthDoesNotOverflow() {
        ToolDisplay display = ToolDisplay.failed(
                "Bash(very long command that would overflow)", "Failed · 1.2s",
                List.of("error: something went terribly wrong with this command"));

        List<String> lines = ToolActivityCardRenderer.card(display, 30);
        for (int i = 0; i < lines.size(); i++) {
            int dw = TerminalText.displayWidth(lines.get(i));
            assertTrue(dw <= 30,
                    "line " + i + " width " + dw + " exceeds 30: " + lines.get(i));
        }
    }

    @Test
    void runningSummaryShowsElapsedSeconds() {
        ToolDisplay display = ToolDisplay.running("Bash(test)", "running");
        String summary = ToolActivityCardRenderer.runningSummary(display, 5);
        assertTrue(summary.contains("running") && summary.contains("5s"),
                "running summary should show elapsed: " + summary);
    }

    @Test
    void statusGlyphReturnsCorrectSymbols() {
        assertEquals("●", strip(ToolActivityCardRenderer.statusGlyph(DisplayStatus.RUNNING)));
        assertEquals("●", strip(ToolActivityCardRenderer.statusGlyph(DisplayStatus.SUCCESS)));
        assertEquals("●", strip(ToolActivityCardRenderer.statusGlyph(DisplayStatus.FAILED)));
        assertEquals("●", strip(ToolActivityCardRenderer.statusGlyph(DisplayStatus.DENIED)));
    }

    // ---- stage / expandable tests -------------------------------------

    @Test
    void stageBuildsVisibleSummaryFromSummaryAndDetails() {
        ToolDisplay display = ToolDisplay.success(
                "Bash(test)", "Done · 1.2s",
                List.of("line 1", "line 2"));

        List<String> visible = ToolActivityCardRenderer.visibleSummary(display);
        assertEquals(3, visible.size());
        assertEquals("Done · 1.2s", visible.get(0));
        assertEquals("line 1", visible.get(1));
        assertEquals("line 2", visible.get(2));
    }

    @Test
    void stageBuildsVisibleSummaryFromDetailsWhenSummaryBlank() {
        ToolDisplay display = ToolDisplay.success(
                "Bash(test)", "", List.of("line 1"));

        List<String> visible = ToolActivityCardRenderer.visibleSummary(display);
        assertEquals(1, visible.size());
        assertEquals("line 1", visible.get(0));
    }

    @Test
    void stageKeepsVerboseTailHidden() {
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("shown"),
                List.of("shown", "hidden1", "hidden2"),
                DisplayStatus.SUCCESS);

        List<String> verbose = ToolActivityCardRenderer.verboseTail(display);
        assertEquals(2, verbose.size());
        assertEquals("hidden1", verbose.get(0));
        assertEquals("hidden2", verbose.get(1));
    }

    @Test
    void hiddenLineCountReturnsVerboseMinusVisibleDetails() {
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("a"),
                List.of("a", "b", "c"),
                DisplayStatus.SUCCESS);

        assertEquals(2, ToolActivityCardRenderer.hiddenLineCount(display));
    }

    @Test
    void hiddenLineCountZeroWhenNoVerboseTail() {
        ToolDisplay display = ToolDisplay.success("Bash(test)", "Done", List.of("a"));

        assertEquals(0, ToolActivityCardRenderer.hiddenLineCount(display));
    }

    @Test
    void stageWithExpandableShowsHasMore() {
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("shown"),
                List.of("shown", "hidden1", "hidden2"),
                DisplayStatus.SUCCESS);

        StageWriter.Stage stage = ToolActivityCardRenderer.stage(display, true);

        assertTrue(stage.hasMore());
        assertEquals(2, stage.summary().size()); // "Done" + "shown"
        assertEquals(2, stage.verbose().size()); // hidden1, hidden2
    }

    @Test
    void stageWithoutExpandableHidesVerbose() {
        ToolDisplay display = new ToolDisplay(
                "Bash(test)", "Done",
                List.of("shown"),
                List.of("shown", "hidden1", "hidden2"),
                DisplayStatus.SUCCESS);

        StageWriter.Stage stage = ToolActivityCardRenderer.stage(display, false);

        assertFalse(stage.hasMore());
        assertTrue(stage.verbose().isEmpty());
    }

    @Test
    void statusBulletHasExpectedPlainGlyphs() {
        // Verify styled bullets contain the expected glyphs (strip ANSI).
        assertTrue(strip(ToolActivityCardRenderer.statusBullet(DisplayStatus.RUNNING)).contains("●"));
        assertTrue(strip(ToolActivityCardRenderer.statusBullet(DisplayStatus.SUCCESS)).contains("●"));
        assertTrue(strip(ToolActivityCardRenderer.statusBullet(DisplayStatus.FAILED)).contains("●"));
        assertTrue(strip(ToolActivityCardRenderer.statusBullet(DisplayStatus.DENIED)).contains("●"));
        assertTrue(strip(ToolActivityCardRenderer.statusBullet(DisplayStatus.INFO)).contains("●"));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String strip(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
