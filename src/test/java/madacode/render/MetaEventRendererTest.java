package madacode.render;

import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;
import madacode.services.compact.CompactResult;
import madacode.tui.TextScreen;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaEventRendererTest {

    @Test
    void metaEventsUseStageTemplateExceptTokenReport() {
        List<MetaEvent> events = List.of(
                new MetaEvent.CompactStarted(10_000, 8_000),
                new MetaEvent.CompactCompleted(new CompactResult(true, 10_000, 4_000, 3, 2, "FullCompact")),
                new MetaEvent.CompactFailed("too large"),
                new MetaEvent.PlanModeEntered(),
                new MetaEvent.PlanModeExited(),
                new MetaEvent.PlanRejected("too risky"));

        for (MetaEvent event : events) {
            String output = render(event);
            assertTrue(output.startsWith("\n"),
                    event + " should own a leading blank line: " + output);
            assertTrue(!output.endsWith("\n\n"),
                    event + " should not rely on trailing blank spacing: " + output);
            assertTrue(output.contains("●"), event + " should use stage bullet: " + output);
            assertTrue(output.contains("─"), event + " should use stage summary: " + output);
        }
    }

    @Test
    void tokenReportSilentByDefault() {
        String output = render(new MetaEvent.TokenReport(new TokenUsage(10, 20, 0, 0), 3, 4));

        assertTrue(output.isBlank(), "expected no scrollback line; got: " + output);
    }

    @Test
    void subAgentStartedIsVisualNoOp() {
        String output = render(new MetaEvent.SubAgentStarted("find README location", "explorer"));

        assertTrue(output.isBlank(), "expected no scrollback line for SubAgentStarted; got: " + output);
    }

    private static String render(MetaEvent event) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MetaEventRenderer renderer = new MetaEventRenderer(
                new TextScreen(new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        renderer.onMetaEvent(event);
        return strip(bytes.toString(StandardCharsets.UTF_8));
    }

    private static String strip(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
