package madacode.render.turn;

import madacode.core.model.ContentBlock;
import madacode.core.model.MetaEvent;
import madacode.tui.Screen;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurnRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class RecScreen implements Screen {
        @Override public void scrollback(List<String> lines) {}
        @Override public void setLiveStatus(List<String> lines) {}
        @Override public void clearLiveStatus() {}
        @Override public int width() { return 80; }
        @Override public int height() { return 30; }
        @Override public void flush() {}
    }

    private TurnView turnView;
    private TurnRenderer renderer;

    @BeforeEach
    void setUp() {
        turnView = new TurnView(new RecScreen());
        renderer = new TurnRenderer(turnView, new RecScreen());
    }

    @Test
    void toolUseWithoutText_dismissesThinking() {
        // iter1: ModelRequestStarted → thinking added
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        assertEquals(1, turnView.items().size(), "thinking should be added");
        assertInstanceOf(TurnStatusRenderable.class, turnView.items().getFirst());

        // iter1: ToolUseBlock arrives directly (no text) → thinking must be dismissed
        ObjectNode input = MAPPER.createObjectNode();
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input));

        List<Renderable> items = turnView.items();
        assertEquals(1, items.size(), "only tool card should remain");
        assertInstanceOf(ToolCardRenderable.class, items.get(0), "thinking must be gone");
    }

    @Test
    void multiIteration_oldThinkingClearedOnNewRequest() {
        // iter1: thinking + tool_use (no text)
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        ObjectNode input = MAPPER.createObjectNode();
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input));
        renderer.onAssistantStreamFinalized(0);

        // iter2: new ModelRequestStarted — old thinking must already be gone
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());

        List<Renderable> items = turnView.items();
        long thinkingCount = items.stream().filter(i -> i instanceof TurnStatusRenderable).count();
        assertEquals(1, thinkingCount, "exactly one thinking (the new one)");
        assertInstanceOf(ToolCardRenderable.class, items.get(0), "tool card first");
        assertInstanceOf(TurnStatusRenderable.class, items.get(1), "new thinking second");
    }

    @Test
    void iter1ToolUse_iter2Text_noOrphanThinking() {
        // iter1: thinking → tool_use (no text)
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        ObjectNode input = MAPPER.createObjectNode();
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input));
        renderer.onAssistantStreamFinalized(0);

        // Tool execution completes
        renderer.onToolExecutionCompleted("t1", true, 13);

        // iter2: thinking → text
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        renderer.onAssistantTextChunk(1, "当前路径：...\n");

        List<Renderable> items = turnView.items();
        for (Renderable item : items) {
            assertFalse(item instanceof TurnStatusRenderable && !((TurnStatusRenderable) item).isFinalized(),
                    "no live thinking should remain");
        }
    }

    @Test
    void errorEvent_clearsThinking() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        assertEquals(1, turnView.items().size());

        renderer.onMetaEvent(new MetaEvent.Error("something broke", null));

        List<Renderable> items = turnView.items();
        assertTrue(items.stream().noneMatch(i -> i instanceof TurnStatusRenderable),
                "thinking should be removed on error");
    }

    @Test
    void streamFinalized_clearsThinking() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        assertEquals(1, turnView.items().size());

        // Stream ends without any text or tool_use
        renderer.onAssistantStreamFinalized(0);

        List<Renderable> items = turnView.items();
        assertTrue(items.isEmpty(), "thinking should be removed on stream finalize");
    }

    @Test
    void endTurn_clearsThinking() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        renderer.endTurn();

        assertTrue(turnView.items().isEmpty());
    }

    @Test
    void assistantTextRemovesStatusLine() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        assertEquals(1, turnView.items().size());

        renderer.onAssistantTextChunk(0, "hello");

        assertEquals(1, turnView.items().size());
        assertInstanceOf(AssistantTextRenderable.class, turnView.items().getFirst());
    }

    @Test
    void toolStartedUpdatesStatusAndToolCompletedRemovesIt() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "foo");
        renderer.onToolExecutionStarted("t1", "grep", input);

        assertEquals(1, turnView.items().size());
        assertInstanceOf(TurnStatusRenderable.class, turnView.items().getFirst());
        String rendered = stripAnsi(turnView.items().getFirst().render(120).getFirst());
        assertTrue(rendered.contains("Searching for \"foo\""), rendered);

        renderer.onToolExecutionCompleted("t1", true, 10);

        assertTrue(turnView.items().isEmpty(), "status line should be removed when no active tools remain");
    }

    @Test
    void toolActivityAppendsToMatchingToolCard() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("subagent_type", "explorer");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("agent-1", "agent", input));

        renderer.onToolExecutionActivity("agent-1", "▸ Reading README.md");

        var lines = turnView.items().getFirst().render(120);
        assertTrue(lines.stream().anyMatch(l -> stripAnsi(l).contains("▸ Reading README.md")), lines.toString());
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
