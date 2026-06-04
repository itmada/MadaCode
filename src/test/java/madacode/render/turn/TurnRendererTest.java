package madacode.render.turn;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
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

        // iter1: ToolUseBlock arrives directly (no text) → thinking must be dismissed.
        // The tool card is already in the render tree, but pure queued cards render empty.
        ObjectNode input = MAPPER.createObjectNode();
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input));

        List<Renderable> items = turnView.items();
        assertEquals(2, items.size(), "tool card plus the pending spinner");
        assertSameTool("t1", items.getFirst());
        assertInstanceOf(TurnStatusRenderable.class, items.get(1), "spinner sits below the card");
        assertTrue(items.getFirst().render(120).isEmpty(), "pure queued tool card should be hidden");

        renderer.onToolExecutionReached("t1", "bash", input);
        assertTrue(items.getFirst().render(120).isEmpty(), "reached no longer reveals queued cards");

        renderer.onToolExecutionStarted("t1", "bash", input);
        assertFalse(items.getFirst().render(120).isEmpty(), "started tool card should be visible");
    }

    @Test
    void multiIteration_oldThinkingClearedOnNewRequest() {
        // iter1: thinking + tool_use (no text)
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        ObjectNode input = MAPPER.createObjectNode();
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input));
        renderer.onAssistantStreamFinalized(0);
        renderer.onToolExecutionReached("t1", "bash", input);

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
    void staticAssistantMessageIsRenderedDuringTurn() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());

        renderer.onMessageAppended(1, Message.assistant("方案已记录，回复“开始执行”。"));

        assertEquals(1, turnView.items().size());
        AssistantTextRenderable text = assertInstanceOf(
                AssistantTextRenderable.class, turnView.items().getFirst());
        String rendered = String.join("\n", text.drainCommittedLines(120))
                .replaceAll("\\[[0-9;]*[a-zA-Z]", "");
        assertTrue(rendered.contains("方案已记录"), rendered);
        assertTrue(rendered.contains("开始执行"), rendered);
    }

    @Test
    void toolStartedUpdatesStatusAndToolCompletedRemovesIt() {
        renderer.onMetaEvent(new MetaEvent.ModelRequestStarted());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "foo");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "grep", input));
        renderer.onToolExecutionStarted("t1", "grep", input);

        assertEquals(2, turnView.items().size());
        assertInstanceOf(ToolCardRenderable.class, turnView.items().getFirst());
        assertInstanceOf(TurnStatusRenderable.class, turnView.items().get(1));
        String rendered = stripAnsi(turnView.items().get(1).render(120).getFirst());
        assertTrue(rendered.contains("Searching for \"foo\""), rendered);

        renderer.onToolExecutionCompleted("t1", true, 10);

        assertTrue(turnView.items().stream().noneMatch(TurnStatusRenderable.class::isInstance),
                "status line should be removed when no active tools remain");
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

    @Test
    void revealsToolCardsInModelOrderAsExecutionReachesThem() {
        ObjectNode input1 = MAPPER.createObjectNode().put("command", "one");
        ObjectNode input2 = MAPPER.createObjectNode().put("command", "two");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input1));
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t2", "bash", input2));

        List<Renderable> items = turnView.items();
        assertEquals(3, items.size(), "two declared tools plus the pending spinner");
        assertSameTool("t1", items.get(0));
        assertSameTool("t2", items.get(1));
        assertInstanceOf(TurnStatusRenderable.class, items.get(2), "spinner sits below the cards");
        assertTrue(items.get(0).render(120).isEmpty(), "pure queued first tool should be hidden");
        assertTrue(items.get(1).render(120).isEmpty(), "pure queued second tool should be hidden");

        renderer.onToolExecutionReached("t2", "bash", input2);
        assertTrue(items.get(1).render(120).isEmpty(), "reached does not reveal later queued tools");

        renderer.onToolExecutionStarted("t2", "bash", input2);
        assertTrue(items.get(0).render(120).isEmpty(), "earlier queued card remains hidden");
        assertFalse(items.get(1).render(120).isEmpty(), "started later card should be visible");

        renderer.onToolExecutionStarted("t1", "bash", input1);
        assertFalse(items.get(0).render(120).isEmpty(), "started first card should be visible");
        assertTrue(renderPlain(items.get(0)).contains("one"));
        assertTrue(renderPlain(items.get(1)).contains("two"));
    }

    @Test
    void approvalShowsInlinePromptWithoutHidingStartedSiblings() {
        ObjectNode input1 = MAPPER.createObjectNode().put("command", "needs approval");
        ObjectNode input2 = MAPPER.createObjectNode().put("command", "later safe");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input1));
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t2", "bash", input2));

        renderer.onToolExecutionReached("t1", "bash", input1);
        List<Renderable> items = turnView.items();
        assertEquals(3, items.size(), "two tool cards added immediately, plus the pending spinner");
        assertSameTool("t1", items.getFirst());
        assertSameTool("t2", items.get(1));
        assertTrue(items.getFirst().render(120).isEmpty(), "unstarted approval tool is hidden before permission");

        renderer.beginPermission("t1");

        items = turnView.items();
        assertEquals(3, items.size());
        assertSameTool("t1", items.getFirst());
        assertTrue(items.getFirst().render(120).stream().anyMatch(l -> stripAnsi(l).contains("Permission required")));
        assertTrue(items.get(1).render(120).isEmpty(), "pure queued sibling remains hidden");

        renderer.onToolExecutionStarted("t2", "bash", input2);

        items = turnView.items();
        assertEquals(3, items.size(), "started sibling plus status should remain in the render tree");
        assertSameTool("t1", items.getFirst());
        assertSameTool("t2", items.get(1));
        assertTrue(renderPlain(items.getFirst()).contains("Permission required"));
        assertTrue(renderPlain(items.get(1)).contains("later safe"),
                "started sibling card should be visible while first card waits for permission");

        renderer.resolvePermission("t1");

        items = turnView.items();
        assertEquals(3, items.size());
        assertSameTool("t1", items.getFirst());
        assertSameTool("t2", items.get(1));
    }

    @Test
    void deniedPermissionKeepsStartedSiblingsVisible() {
        ObjectNode input1 = MAPPER.createObjectNode().put("command", "deny this");
        ObjectNode input2 = MAPPER.createObjectNode().put("command", "later safe");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input1));
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t2", "bash", input2));

        renderer.onToolExecutionReached("t1", "bash", input1);
        renderer.beginPermission("t1");
        renderer.onToolExecutionStarted("t2", "bash", input2);
        renderer.resolvePermission("t1", true);

        List<Renderable> items = turnView.items();
        assertEquals(3, items.size(), "ordinary deny should not hide started siblings");
        assertSameTool("t1", items.getFirst());
        assertSameTool("t2", items.get(1));
        assertTrue(renderPlain(items.getFirst()).contains("Permission denied"));
        assertTrue(renderPlain(items.get(1)).contains("later safe"));
    }

    @Test
    void cancelledToolRendersCompactAndStaysVisible() {
        ObjectNode input1 = MAPPER.createObjectNode().put("command", "cancel here");
        ObjectNode input2 = MAPPER.createObjectNode().put("command", "later safe");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input1));
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t2", "bash", input2));

        renderer.onToolExecutionReached("t1", "bash", input1);
        renderer.beginPermission("t1");
        renderer.cancelPermission("t1");
        renderer.onToolResultAvailable("t2", false, "Cancelled before execution: permission denied");
        renderer.onToolExecutionCompleted("t2", false, 0);

        List<Renderable> items = turnView.items();
        assertEquals(2, items.size(), "skipped tool stays visible but renders compact");
        assertSameTool("t1", items.getFirst());
        assertSameTool("t2", items.get(1));
        String rendered = renderPlain(items.get(1));
        assertTrue(rendered.contains("Cancelled"), rendered);
        assertTrue(rendered.contains("Cancelled before execution: permission denied"), rendered);
    }

    @Test
    void concurrentStartedToolsAreVisibleInModelOrder() {
        ObjectNode input1 = MAPPER.createObjectNode().put("command", "first concurrent");
        ObjectNode input2 = MAPPER.createObjectNode().put("command", "second concurrent");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input1));
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t2", "bash", input2));

        renderer.onToolExecutionStarted("t2", "bash", input2);
        renderer.onToolExecutionStarted("t1", "bash", input1);

        List<Renderable> items = turnView.items();
        assertEquals(3, items.size(), "two cards plus status line");
        assertSameTool("t1", items.get(0));
        assertSameTool("t2", items.get(1));
        assertTrue(renderPlain(items.get(0)).contains("first concurrent"));
        assertTrue(renderPlain(items.get(1)).contains("second concurrent"));
    }

    @Test
    void permissionWaitingToolDoesNotHideStartedSibling() {
        ObjectNode input1 = MAPPER.createObjectNode().put("command", "needs approval");
        ObjectNode input2 = MAPPER.createObjectNode().put("command", "safe sibling");
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t1", "bash", input1));
        renderer.onAssistantBlockAppended(0, new ContentBlock.ToolUseBlock("t2", "bash", input2));

        renderer.beginPermission("t1");
        renderer.onToolExecutionStarted("t2", "bash", input2);

        List<Renderable> items = turnView.items();
        assertSameTool("t1", items.get(0));
        assertSameTool("t2", items.get(1));
        assertTrue(renderPlain(items.get(0)).contains("Permission required"));
        assertTrue(renderPlain(items.get(1)).contains("safe sibling"));
    }

    private static void assertSameTool(String expectedId, Renderable item) {
        assertInstanceOf(ToolCardRenderable.class, item);
        assertEquals(expectedId, ((ToolCardRenderable) item).toolUseId());
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }

    private static String renderPlain(Renderable item) {
        return String.join("\n", item.render(120)).replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }
}
