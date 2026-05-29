package madacode.tui.widget;

import madacode.tui.TerminalText;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalPanelTest {

    @Test
    void rendersTitleToolAndDetail() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required",
                "bash wants to run",
                "rm -rf /tmp/scratch",
                defaultActions(),
                0,
                "↑/↓ select   Enter confirm   Esc deny");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 80);
        String rendered = join(lines);
        assertTrue(rendered.contains("permission required"), "should contain title: " + rendered);
        assertTrue(rendered.contains("bash wants to run"), "should contain subject: " + rendered);
        assertTrue(rendered.contains("rm -rf /tmp/scratch"), "should contain detail: " + rendered);
    }

    @Test
    void rendersThreeOptions() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "↑/↓ select   Enter confirm   Esc deny");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);
        assertAllLinesFit(lines, 80);
        String rendered = join(lines);

        assertTrue(rendered.contains("Deny"), "should contain Deny option");
        assertTrue(rendered.contains("Allow once"), "should contain Allow once option");
        assertTrue(rendered.contains("Allow for session"), "should contain Allow for session option");
    }

    @Test
    void defaultSelectedDenyHasCursorIndicator() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "↑/↓ select   Enter confirm   Esc deny");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);
        String rendered = join(lines);

        assertTrue(rendered.contains("> Deny"), "Deny should have > cursor: " + rendered);
        assertFalse(rendered.contains("> Allow once"), "Allow once should not have > when unselected");
    }

    @Test
    void selectedIndexChangesCursorPosition() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 1, "↑/↓ select   Enter confirm   Esc deny");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);
        String rendered = join(lines);

        assertTrue(rendered.contains("> Allow once"), "Allow once should have > when selectedIndex=1: " + rendered);
        assertFalse(rendered.contains("> Deny"), "Deny should not have > when not selected");
    }

    @Test
    void footerContainsNavigationHints() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "↑/↓ select   Enter confirm   Esc deny");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);
        String rendered = join(lines);

        assertTrue(rendered.contains("Enter confirm"), "footer should contain Enter hint");
        assertTrue(rendered.contains("Esc deny"), "footer should contain Esc hint");
    }

    @Test
    void longDetailIsTruncated() {
        String longCmd = "echo " + "x".repeat(200);
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", longCmd,
                defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 40);
        assertAllLinesFit(lines, 40);

        for (AttributedString line : lines) {
            String plain = line.toString();
            assertTrue(plain.length() < 200,
                    "line should not be excessively long: " + plain);
        }
    }

    @Test
    void narrowWidth30DoesNotThrow() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 30);
        assertFalse(lines.isEmpty(), "should render even with narrow width");
        assertAllLinesFit(lines, 30);
    }

    @Test
    void width10DoesNotThrow() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 10);
        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 10);
    }

    @Test
    void width2DoesNotThrow() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 2);
        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 2);
    }

    @Test
    void width1DoesNotThrow() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 1);
        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 1);
    }

    @Test
    void width0DegradesTo1() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "permission required", "bash wants to run", "echo hi",
                defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 0);
        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 1); // width 0 is clamped to 1
    }

    @Test
    void emptySubjectAndDetailRenderWithoutError() {
        ApprovalPanel.ApprovalRequestView view = new ApprovalPanel.ApprovalRequestView(
                "confirm", "", "", defaultActions(), 0, "footer");

        List<AttributedString> lines = ApprovalPanel.render(view, 80);

        assertFalse(lines.isEmpty());
        String rendered = join(lines);
        assertTrue(rendered.contains("confirm"), "should contain title");
    }

    // ---- inline approval tests -------------------------------------------

    @Test
    void renderInlineApprovalWidth10EveryLineFits() {
        var lines = ApprovalPanel.renderInlineApproval(10, 0);
        assertFalse(lines.isEmpty());
        assertAllLinesFitString(lines, 10);
    }

    @Test
    void renderInlineApprovalWidth20EveryLineFits() {
        var lines = ApprovalPanel.renderInlineApproval(20, 0);
        assertFalse(lines.isEmpty());
        assertAllLinesFitString(lines, 20);
    }

    @Test
    void renderInlineApprovalWidth30EveryLineFits() {
        var lines = ApprovalPanel.renderInlineApproval(30, 0);
        assertFalse(lines.isEmpty());
        assertAllLinesFitString(lines, 30);
    }

    @Test
    void renderInlineApprovalWidth5EveryLineFits() {
        var lines = ApprovalPanel.renderInlineApproval(5, 1);
        assertFalse(lines.isEmpty());
        assertAllLinesFitString(lines, 5);
    }

    @Test
    void renderInlineApprovalWidth1EveryLineFits() {
        var lines = ApprovalPanel.renderInlineApproval(1, 0);
        assertFalse(lines.isEmpty());
        assertAllLinesFitString(lines, 1);
    }

    @Test
    void renderInlineApprovalSelectedIndexChangesCursor() {
        var lines0 = ApprovalPanel.renderInlineApproval(40, 0);
        var lines1 = ApprovalPanel.renderInlineApproval(40, 1);
        assertNotEquals(joinString(lines0), joinString(lines1),
                "different selected indices should produce different output");
    }

    // ---- helpers -------------------------------------------------------

    private static List<ApprovalPanel.Action> defaultActions() {
        return List.of(
                new ApprovalPanel.Action(ApprovalPanel.Decision.DENY, "Deny", true),
                new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_ONCE, "Allow once", false),
                new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_SESSION, "Allow for session", false));
    }

    private static String join(List<AttributedString> lines) {
        StringBuilder sb = new StringBuilder();
        for (AttributedString line : lines) {
            sb.append(line.toString()).append('\n');
        }
        return sb.toString();
    }

    private static void assertAllLinesFit(List<AttributedString> lines, int width) {
        int safeWidth = Math.max(1, width);
        for (int i = 0; i < lines.size(); i++) {
            int displayWidth = TerminalText.displayWidth(lines.get(i).toString());
            assertTrue(displayWidth <= safeWidth,
                    "line " + i + " display width " + displayWidth
                    + " exceeds requested width " + safeWidth
                    + ": \"" + lines.get(i).toString() + "\"");
        }
    }

    private static void assertAllLinesFitString(List<String> lines, int width) {
        int safeWidth = Math.max(1, width);
        for (int i = 0; i < lines.size(); i++) {
            int displayWidth = TerminalText.displayWidth(lines.get(i));
            assertTrue(displayWidth <= safeWidth,
                    "line " + i + " display width " + displayWidth
                    + " exceeds requested width " + safeWidth
                    + ": \"" + lines.get(i) + "\"");
        }
    }

    private static String joinString(List<String> lines) {
        return String.join("\n", lines);
    }
}
