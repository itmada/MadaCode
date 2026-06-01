package madacode.tui;

import madacode.render.tool.ToolActivityCardRenderer;
import madacode.render.tool.ToolDisplay;
import madacode.tui.widget.ApprovalPanel;
import madacode.tui.widget.ChoicePanel;
import madacode.tui.widget.CommandPalettePanel;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot-ish tests covering key TUI output.
 * Verifies: display width fit, no old text patterns, no long JSON dumps.
 */
class TuiSnapshotTest {

    // ---- approval panel ------------------------------------------------

    @Test
    void approvalPanelAt80Columns() {
        var view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run",
                "{\"command\":\"rm -rf /tmp/demo\"}",
                List.of(
                        new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_ONCE, "allow once", false),
                        new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_SESSION, "allow session", false),
                        new ApprovalPanel.Action(ApprovalPanel.Decision.DENY, "deny", true)),
                0, "←/→ select   Enter confirm   Esc deny");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);
        assertAllLinesFit(lines, 80);
        String output = plain(lines);

        assertTrue(output.contains("permission required"));
        assertTrue(output.contains("bash wants to run"));
        assertTrue(output.contains("› allow once"));
        assertTrue(output.contains("allow session"));
        assertTrue(output.contains("deny"));
        assertTrue(output.contains("Enter confirm"));
        assertFalse(output.contains("╰─"), "approval panel should stay open at the bottom");
        // No old patterns
        assertFalse(output.contains("[y]"), "should not contain old [y] pattern");
        assertFalse(output.contains("[n]"), "should not contain old [n] pattern");
        assertFalse(output.contains("y=once"), "should not contain old y=once text");
    }

    @Test
    void approvalPanelAtNarrowWidths() {
        var view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                List.of(
                        new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_ONCE, "allow once", false),
                        new ApprovalPanel.Action(ApprovalPanel.Decision.DENY, "deny", true)),
                0, "footer");

        for (int w : new int[] {10, 20, 30, 40}) {
            List<AttributedString> lines = ApprovalPanel.render(view, w);
            assertFalse(lines.isEmpty(), "should render at width=" + w);
            assertAllLinesFit(lines, w);
        }
    }

    // ---- choice panel --------------------------------------------------

    @Test
    void choicePanelAt80Columns() {
        var view = new ChoicePanel.ChoiceView(
                "Model", "Choose model for subsequent turns",
                List.of(
                        new ChoicePanel.ChoiceOption("claude-sonnet-4-6", "", ""),
                        new ChoicePanel.ChoiceOption("claude-opus-4-7", "", "")),
                0, "↑/↓ select   Enter confirm   Esc cancel");

        List<AttributedString> lines = ChoicePanel.render(view, 80);
        assertAllLinesFit(lines, 80);
        String output = plain(lines);

        assertEquals("", lines.getFirst().toString(),
                "choice panel should own a top margin before the card");
        assertTrue(output.contains("── Model"));
        assertTrue(output.contains("Choose model"));
        assertTrue(output.contains("claude-sonnet-4-6"));
        assertTrue(output.contains("claude-opus-4-7"));
        assertTrue(output.contains("› claude-sonnet-4-6"));
        assertTrue(output.contains("  claude-opus-4-7"));
        assertTrue(output.contains("Enter confirm"));
        assertTrue(output.contains("Esc cancel"));
        assertFalse(output.contains("╭"));
        assertFalse(output.contains("│"));
        assertFalse(output.contains("╰"));
    }

    @Test
    void choicePanelAtNarrowWidths() {
        var view = new ChoicePanel.ChoiceView(
                "Model", "Choose model",
                List.of(new ChoicePanel.ChoiceOption("option-a", "", "")),
                0, "footer");

        for (int w : new int[] {10, 20, 30, 40}) {
            List<AttributedString> lines = ChoicePanel.render(view, w);
            assertFalse(lines.isEmpty(), "should render at width=" + w);
            assertAllLinesFit(lines, w);
        }
    }

    @Test
    void commandPaletteUsesDividerAndGutterLayout() {
        var view = new CommandPalettePanel.View(
                "Commands",
                "/mo",
                3,
                List.of(
                        new CommandPalettePanel.PaletteCandidate("/model", "Choose model"),
                        new CommandPalettePanel.PaletteCandidate("/mode", "Choose mode")),
                0,
                "Tab complete   Enter confirm   Esc cancel");

        List<AttributedString> lines = CommandPalettePanel.render(view, 80);
        assertAllLinesFit(lines, 80);
        String output = plain(lines);

        assertTrue(output.contains("── Commands"));
        assertTrue(output.contains("/mo"));
        assertTrue(output.contains("› /model"));
        assertTrue(output.contains("  /mode"));
        assertTrue(output.contains("Esc cancel"));
        assertFalse(output.contains("╭"));
        assertFalse(output.contains("│"));
        assertFalse(output.contains("╰"));
        assertFalse(output.contains("────\n────"));
        assertFalse(output.contains("Choose model"));
    }

    // ---- tool activity cards -------------------------------------------

    @Test
    void toolRunningRow() {
        ToolDisplay display = ToolDisplay.running("Bash(npm test)", "running");
        String summary = ToolActivityCardRenderer.runningSummary(display, 3);

        assertTrue(summary.contains("running"), "should contain running status");
        assertTrue(summary.contains("3s"), "should contain elapsed seconds");
    }

    @Test
    void toolSuccessCard() {
        ToolDisplay display = ToolDisplay.success(
                "Bash(./mvnw test)", "BUILD SUCCESS · 3.8s",
                List.of("Tests run: 42, Failures: 0, Errors: 0"));

        List<String> lines = ToolActivityCardRenderer.card(display, 80);
        assertAllPlainLinesFit(lines, 80);
        String output = String.join("\n", lines);
        String stripped = stripAnsi(output);

        assertTrue(stripped.contains("Bash    ./mvnw test"));
        assertTrue(stripped.contains("BUILD SUCCESS"));
        assertFalse(stripped.contains("[y]"), "should not contain old patterns");
    }

    @Test
    void toolDeniedCard() {
        ToolDisplay display = ToolDisplay.denied(
                "Bash(rm -rf /)", "Permission denied",
                List.of("User denied permission for tool: bash"));

        List<String> lines = ToolActivityCardRenderer.card(display, 80);
        String output = String.join("\n", lines);
        String stripped = stripAnsi(output);

        assertTrue(stripped.contains("Permission denied"));
        assertTrue(stripped.contains("Bash    rm -rf /"));
        assertFalse(output.contains("{\"command\""),
                "should not dump raw JSON in denied card");
        assertFalse(output.contains("insert file"),
                "should not contain old 'insert file' text");
    }

    @Test
    void toolFailedCard() {
        ToolDisplay display = ToolDisplay.failed(
                "Bash(npm install)", "Failed · 820ms",
                List.of("npm ERR! code E404"));

        List<String> lines = ToolActivityCardRenderer.card(display, 80);
        String output = String.join("\n", lines);
        String stripped = stripAnsi(output);

        assertTrue(stripped.contains("Failed"));
        assertTrue(stripped.contains("npm ERR! code E404"));
    }

    @Test
    void toolCardsAtNarrowWidths() {
        ToolDisplay display = ToolDisplay.failed(
                "Bash(very long command that would overflow badly here)",
                "Failed · 1.2s",
                List.of("error: something went terribly wrong with this command"));

        for (int w : new int[] {10, 20, 30, 40}) {
            List<String> lines = ToolActivityCardRenderer.card(display, w);
            assertAllPlainLinesFit(lines, w);
        }
    }

    @Test
    void noLongJsonPavedOnScreen() {
        // Even with long input, tool cards should truncate, not dump raw JSON.
        String longJson = "{\"command\":\"" + "x".repeat(200) + "\"}";
        ToolDisplay display = ToolDisplay.failed("Bash(something)", longJson, List.of());

        List<String> lines = ToolActivityCardRenderer.card(display, 80);
        String output = String.join("\n", lines);
        // The long JSON shouldn't appear verbatim — it should be truncated in the title.
        assertFalse(output.contains("x".repeat(200)),
                "should not dump raw long JSON in card");
    }

    // ---- helpers -------------------------------------------------------

    private static void assertAllLinesFit(List<AttributedString> lines, int width) {
        int safeWidth = Math.max(1, width);
        for (int i = 0; i < lines.size(); i++) {
            int dw = TerminalText.displayWidth(lines.get(i).toString());
            assertTrue(dw <= safeWidth,
                    "line " + i + " width " + dw + " > " + safeWidth + ": \"" + lines.get(i) + "\"");
        }
    }

    private static void assertAllPlainLinesFit(List<String> lines, int width) {
        int safeWidth = Math.max(1, width);
        for (int i = 0; i < lines.size(); i++) {
            String stripped = stripAnsi(lines.get(i));
            int dw = TerminalText.displayWidth(stripped);
            assertTrue(dw <= safeWidth,
                    "line " + i + " width " + dw + " > " + safeWidth + ": \"" + stripped + "\"");
        }
    }

    private static String plain(List<AttributedString> lines) {
        StringBuilder sb = new StringBuilder();
        for (AttributedString line : lines) {
            sb.append(line.toString()).append('\n');
        }
        return sb.toString();
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
