package madacode.render.turn;

import madacode.render.tool.ToolDisplayRegistry;
import madacode.render.tool.ToolProgressLine;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ToolCardRenderableTest {

    private final ToolDisplayRegistry registry = ToolDisplayRegistry.defaults();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode input = mapper.createObjectNode().put("command", "echo hi");

    @Test
    void shouldRenderRunningCard() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.markStarted();
        var lines = card.render(80);
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("Bash"));
        assertFalse(card.isFinalized());
    }

    @Test
    void pureQueuedCardRendersEmpty() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        assertTrue(card.isPureQueued());
        assertTrue(card.render(80).isEmpty());
    }

    @Test
    void shouldBeFinalizedAfterComplete() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.finalizeTool(true, 150);
        assertTrue(card.isFinalized());
        var lines = card.render(80);
        assertTrue(lines.stream().anyMatch(l -> stripAnsi(l).contains("passed")));
    }

    @Test
    void shouldShowPermissionLinesWhenWaiting() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.enterPermissionPhase();
        assertEquals(ToolCardRenderable.PermissionPhase.WAITING, card.permissionPhase());
        var lines = card.render(80);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Permission required")));
        assertFalse(card.isFinalized());
    }

    @Test
    void shouldRemovePermissionLinesAfterResolve() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.enterPermissionPhase();
        card.resolvePermission();
        assertEquals(ToolCardRenderable.PermissionPhase.RESOLVED, card.permissionPhase());
        var lines = card.render(80);
        assertTrue(lines.stream().noneMatch(l -> l.contains("Permission required")));
    }

    @Test
    void shouldMarkDenied() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.markDenied("Not allowed");
        assertTrue(card.isFinalized());
        var lines = card.render(80);
        assertTrue(lines.get(0).contains("Bash"));
        assertTrue(lines.stream().anyMatch(l -> stripAnsi(l).contains("Permission denied")));
        assertTrue(lines.stream().anyMatch(l -> stripAnsi(l).contains("Not allowed")));
    }

    @Test
    void shouldShowProgressLines() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.appendProgress("line 1");
        card.appendProgress("line 2");
        var lines = card.render(80);
        assertTrue(lines.stream().anyMatch(l -> l.contains("line 1")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("line 2")));
    }

    @Test
    void shouldRenderSuccessGlyphAfterFinalizeToolTrue() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.finalizeTool(true, 123);
        var lines = card.render(80);
        assertTrue(lines.get(0).contains("●"),
                "success card should show ● glyph: " + lines.get(0));
    }

    @Test
    void shouldRenderFailureGlyphAfterFinalizeToolFalse() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.finalizeTool(false, 50);
        var lines = card.render(80);
        assertTrue(lines.get(0).contains("●"),
                "failed card should show ● glyph: " + lines.get(0));
    }

    @Test
    void shouldPreserveDeniedAfterFinalizeTool() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        card.markDenied("User denied permission");

        // Tool execution system fires onToolExecutionCompleted(false, ...)
        card.finalizeTool(false, 100);

        // DENIED should be preserved — render still shows denied reason
        assertTrue(card.isFinalized());
        var lines = card.render(80);
        assertTrue(lines.stream().anyMatch(l -> l.contains("User denied permission")),
                "denied reason preserved: " + lines);
    }

    @Test
    void agentCardUsesSingleHeaderAndLifecycleContent() {
        ObjectNode agentInput = mapper.createObjectNode();
        agentInput.put("subagent_type", "explorer");
        agentInput.put("description", "find README location");
        agentInput.put("prompt", "search for README");

        ToolCardRenderable card = new ToolCardRenderable("agent-1", "agent", agentInput, registry);
        card.appendProgress(ToolProgressLine.activity("▸ Reading README.md"));
        card.appendProgress(ToolProgressLine.activity("▸ Searching for \"README\""));
        var running = card.render(120);
        assertEquals(1, countMatches(running, "Agent\\s+explorer"));
        assertTrue(containsPlain(running, "find README location"),
                "running summary should use description: " + running);
        assertTrue(containsPlain(running, "2 tool uses"), running.toString());
        assertTrue(containsPlain(running, "▸ Reading README.md"), running.toString());

        card.setResultOutput(false, "Sub-agent did not complete (MAX_ITERATIONS): still exploring");
        card.finalizeTool(false, 15636);

        var failed = card.render(120);
        assertEquals(1, countMatches(failed, "Agent\\s+explorer"));
        assertEquals(2, failed.size(), "failed lifecycle card should stay compact: " + failed);
        assertTrue(containsPlain(failed, "failed"),
                "final lifecycle card should surface adapter summary: " + failed);
        assertFalse(containsPlain(failed, "Sub-agent did not complete (MAX_ITERATIONS)"),
                "sub-agent failure details should stay in tool_result for the parent agent: " + failed);
    }

    @Test
    void progressRetentionKeepsTailAndCountsHiddenLines() {
        ToolCardRenderable card = new ToolCardRenderable("id1", "bash", input, registry);
        for (int i = 1; i <= 250; i++) {
            card.appendProgress("line " + i);
        }

        var lines = card.render(120);
        assertTrue(containsPlain(lines, "240 earlier lines hidden"), lines.toString());
        assertFalse(containsPlain(lines, "line 1"), lines.toString());
        assertTrue(containsPlain(lines, "line 241"), lines.toString());
        assertTrue(containsPlain(lines, "line 250"), lines.toString());
    }

    @Test
    void agentActivityCountIncludesDroppedActivityLines() {
        ObjectNode agentInput = mapper.createObjectNode();
        agentInput.put("subagent_type", "explorer");
        agentInput.put("description", "long running investigation");

        ToolCardRenderable card = new ToolCardRenderable("agent-1", "agent", agentInput, registry);
        for (int i = 1; i <= 250; i++) {
            card.appendProgress(ToolProgressLine.activity("▸ Reading file-" + i + ".java"));
        }

        var lines = card.render(120);
        assertTrue(containsPlain(lines, "250 tool uses"), lines.toString());
        assertTrue(containsPlain(lines, "246 earlier activities hidden"), lines.toString());
        assertTrue(containsPlain(lines, "file-250.java"), lines.toString());
    }

    private static boolean containsPlain(Iterable<String> lines, String expected) {
        for (String line : lines) {
            if (stripAnsi(line).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static int countMatches(Iterable<String> lines, String regex) {
        Pattern pattern = Pattern.compile(regex);
        int count = 0;
        for (String line : lines) {
            if (pattern.matcher(stripAnsi(line)).find()) {
                count++;
            }
        }
        return count;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
