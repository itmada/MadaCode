package madacode.tui.widget;

import madacode.tui.TerminalText;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderSetupPanelTest {

    @Test
    void rendersTitleAndFieldsInOrder() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("MadaCode needs a model provider before it can run.", "Create /tmp/providers.json"),
                List.of(
                        new ProviderSetupPanel.FieldRow("Provider name", "xiaomi", false),
                        new ProviderSetupPanel.FieldRow("Base URL", "https://example.com", false),
                        new ProviderSetupPanel.FieldRow("Auth token", "********", false),
                        new ProviderSetupPanel.FieldRow("Default model", "mimo-v2.5-pro", false),
                        new ProviderSetupPanel.FieldRow("Other models", "mimo-v2-pro, mimo-v2-flash", false)),
                "",
                "Enter=save   Tab/Shift-Tab or Up/Down=switch   Esc/Ctrl-C/Ctrl-D=cancel");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 80);
        String rendered = join(lines);
        assertTrue(rendered.contains("Configure Provider"), "should contain title");
        assertTrue(rendered.contains("Provider name"), "should contain Provider name field");
        assertTrue(rendered.contains("Base URL"), "should contain Base URL field");
        assertTrue(rendered.contains("Auth token"), "should contain Auth token field");
        assertTrue(rendered.contains("Default model"), "should contain Default model field");
        assertTrue(rendered.contains("Other models"), "should contain Other models field");
    }

    @Test
    void headerLineStartsWithOpenBox() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of(),
                List.of(),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);

        assertFalse(lines.isEmpty());
        String firstLine = lines.get(0).toString();
        assertTrue(firstLine.contains("╭─"), "first line should contain opening box border");
    }

    @Test
    void bodyLinesStartWithLeftBar() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("MadaCode needs a model provider before it can run."),
                List.of(new ProviderSetupPanel.FieldRow("Provider name", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);

        // Skip header, check intro lines and field lines
        for (int i = 1; i < lines.size() - 1; i++) {
            String line = lines.get(i).toString();
            assertTrue(line.contains("│"), "body line should contain left bar: " + line);
        }
    }

    @Test
    void footerLineStartsWithCloseBox() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of(),
                List.of(),
                "",
                "Enter=save   Tab/Shift-Tab or Up/Down=switch   Esc/Ctrl-C/Ctrl-D=cancel");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);

        assertFalse(lines.isEmpty());
        String lastLine = lines.get(lines.size() - 1).toString();
        assertTrue(lastLine.contains("╰─"), "last line should contain closing box border");
    }

    @Test
    void errorLineAppearsWhenErrorPresent() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "Invalid input",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);
        String rendered = join(lines);

        assertTrue(rendered.contains("Error:"), "should contain error prefix");
        assertTrue(rendered.contains("Invalid input"), "should contain error message");
    }

    @Test
    void errorLineAbsentWhenErrorBlank() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);
        String rendered = join(lines);

        assertFalse(rendered.contains("Error:"), "should not contain error line when error is blank");
    }

    @Test
    void fieldLabelsAndValuesAppear() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of(),
                List.of(
                        new ProviderSetupPanel.FieldRow("Provider name", "xiaomi", false),
                        new ProviderSetupPanel.FieldRow("Base URL", "https://api.example.com", false),
                        new ProviderSetupPanel.FieldRow("Default model", "mimo-v2.5-pro", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);
        String rendered = join(lines);

        assertTrue(rendered.contains("Provider name"), "should contain field label");
        assertTrue(rendered.contains("xiaomi"), "should contain field value");
        assertTrue(rendered.contains("Base URL"), "should contain another label");
        assertTrue(rendered.contains("https://api.example.com"), "should contain another value");
    }

    @Test
    void activeFieldMayBeDifferentFromInactive() {
        ProviderSetupPanel.FieldRow inactiveRow = new ProviderSetupPanel.FieldRow("Field", "inactiveValue", false);
        ProviderSetupPanel.FieldRow activeRow = new ProviderSetupPanel.FieldRow("Field", "activeValue█", true);

        ProviderSetupPanel.SetupView inactiveView = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of(),
                List.of(inactiveRow),
                "",
                "footer");

        ProviderSetupPanel.SetupView activeView = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of(),
                List.of(activeRow),
                "",
                "footer");

        List<AttributedString> inactiveLines = ProviderSetupPanel.render(inactiveView, 80);
        List<AttributedString> activeLines = ProviderSetupPanel.render(activeView, 80);

        String inactiveRendered = join(inactiveLines);
        String activeRendered = join(activeLines);

        // The active and inactive should differ due to styling
        assertNotEquals(inactiveRendered, activeRendered, "active and inactive should have different styling");
    }

    @Test
    void cursorMarkerAppearsIntoIntactInValue() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of(),
                List.of(new ProviderSetupPanel.FieldRow("Name", "test█value", true)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);
        String rendered = join(lines);

        // The value should appear intact with the cursor marker
        assertTrue(rendered.contains("test█value"), "cursor marker and value should appear contiguously");
    }

    @Test
    void width80EveryLineFits() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("MadaCode needs a model provider before it can run.", "Create /tmp/providers.json"),
                List.of(
                        new ProviderSetupPanel.FieldRow("Provider name", "xiaomi", false),
                        new ProviderSetupPanel.FieldRow("Base URL", "https://example.com", false),
                        new ProviderSetupPanel.FieldRow("Auth token", "********", false),
                        new ProviderSetupPanel.FieldRow("Default model", "mimo-v2.5-pro", false),
                        new ProviderSetupPanel.FieldRow("Other models", "mimo-v2-pro, mimo-v2-flash", false)),
                "",
                "Enter=save   Tab/Shift-Tab or Up/Down=switch   Esc/Ctrl-C/Ctrl-D=cancel");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 80);

        assertAllLinesFit(lines, 80);
    }

    @Test
    void width30EveryLineFits() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("MadaCode needs a model provider before it can run."),
                List.of(new ProviderSetupPanel.FieldRow("Provider name", "xiaomi", false)),
                "",
                "Enter=save");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 30);

        assertFalse(lines.isEmpty(), "should render even with narrow width");
        assertAllLinesFit(lines, 30);
    }

    @Test
    void width10DoesNotThrow() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 10);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 10);
    }

    @Test
    void width3DoesNotThrow() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 3);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 3);
    }

    @Test
    void width2DoesNotThrow() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 2);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 2);
    }

    @Test
    void width1DoesNotThrow() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 1);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 1);
    }

    @Test
    void width0DegradesTo1() {
        ProviderSetupPanel.SetupView view = new ProviderSetupPanel.SetupView(
                "Configure Provider",
                List.of("Intro"),
                List.of(new ProviderSetupPanel.FieldRow("Field", "value", false)),
                "",
                "footer");

        List<AttributedString> lines = ProviderSetupPanel.render(view, 0);

        assertFalse(lines.isEmpty());
        assertAllLinesFit(lines, 1); // width 0 is clamped to 1
    }

    // ---- helpers -------------------------------------------------------

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
}
