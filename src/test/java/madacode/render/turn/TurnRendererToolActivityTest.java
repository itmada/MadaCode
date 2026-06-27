package madacode.render.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.tui.Screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnRendererToolActivityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void consecutiveExplorationToolsCollapseToOneGroup() {
        Harness h = new Harness();
        ObjectNode read = input("path", "/tmp/README.md");
        ObjectNode glob = input("pattern", "*.java");
        ObjectNode grep = input("pattern", "Foo").put("path", "src/main/java");

        h.appendTool("r1", "file_read", read);
        h.appendTool("g1", "glob", glob);
        h.appendTool("s1", "grep", grep);
        h.complete("r1", "file_read", read, true, "a\nb\nc");
        h.complete("g1", "glob", glob, true, "A.java\nB.java");
        h.complete("s1", "grep", grep, true, "src/main/java/A.java:1:Foo");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertEquals(1, count(lines, "Explored"), "exploration calls should share one group");
        assertTrue(hasLine(lines, "Read /tmp/README.md"));
        assertTrue(hasLine(lines, "List \"*.java\""));
        assertTrue(hasLine(lines, "Search \"Foo\" in src/main/java"));
    }

    @Test
    void standaloneToolBreaksExplorationGroup() {
        Harness h = new Harness();
        ObjectNode firstRead = input("path", "/tmp/a.txt");
        ObjectNode edit = input("file_path", "/tmp/a.txt");
        ObjectNode secondRead = input("path", "/tmp/b.txt");

        h.appendTool("r1", "file_read", firstRead);
        h.complete("r1", "file_read", firstRead, true, "a");
        h.appendTool("e1", "edit", edit);
        h.complete("e1", "edit", edit, true, "File updated successfully");
        h.appendTool("r2", "file_read", secondRead);
        h.complete("r2", "file_read", secondRead, true, "b");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertEquals(2, count(lines, "Explored"), "edit should break exploration grouping");
        assertTrue(hasLine(lines, "file_edit /tmp/a.txt"), "edit remains an independent card");
    }

    @Test
    void failedExplorationItemRemainsVisibleInGroup() {
        Harness h = new Harness();
        ObjectNode grep = input("pattern", "Missing").put("path", "src");

        h.appendTool("s1", "grep", grep);
        h.complete("s1", "grep", grep, false, "ERROR boom\nExit code: 1");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertEquals(1, count(lines, "Explored"));
        assertTrue(hasLine(lines, "1 failed"));
        assertTrue(hasLine(lines, "ERROR boom"));
    }

    @Test
    void permissionPromptRendersInsideExplorationGroup() {
        Harness h = new Harness();
        ObjectNode bash = input("command", "ls -la /tmp");

        h.appendTool("b1", "bash", bash);
        h.renderer.beginPermission("b1");
        h.view.flushNow();

        List<String> live = strip(h.screen.live);
        assertTrue(hasLine(live, "Exploring"));
        assertTrue(hasLine(live, "Permission required"));
        assertTrue(hasLine(live, "ls -la /tmp"));
    }

    private ObjectNode input(String key, String value) {
        return mapper.createObjectNode().put(key, value);
    }

    private static boolean hasLine(List<String> lines, String needle) {
        return lines.stream().anyMatch(line -> line.contains(needle));
    }

    private static long count(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).count();
    }

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(s -> s.replaceAll("\\e\\[[;\\d]*m", "")).toList();
    }

    private final class Harness {
        final CaptureScreen screen = new CaptureScreen();
        final TurnView view = new TurnView(screen);
        final TurnRenderer renderer = new TurnRenderer(view, screen);

        void appendTool(String id, String name, ObjectNode input) {
            renderer.onAssistantBlockAppended(
                    0,
                    new ContentBlock.ToolUseBlock(id, name, input));
        }

        void complete(String id, String name, ObjectNode input, boolean success, String output) {
            renderer.onToolExecutionStarted(id, name, input);
            renderer.onToolResultAvailable(id, success, output);
            renderer.onToolExecutionCompleted(id, success, 7);
            renderer.onMessageAppended(0, Message.user(List.of(
                    new ContentBlock.ToolResultBlock(id, output, success, 7))));
        }
    }

    private static final class CaptureScreen implements Screen {
        final List<String> scrollback = new ArrayList<>();
        volatile List<String> live = List.of();

        @Override
        public synchronized void scrollback(List<String> lines) {
            scrollback.addAll(lines);
        }

        @Override
        public synchronized void setLiveStatus(List<String> lines) {
            live = List.copyOf(lines);
        }

        @Override
        public synchronized void commitScrollbackAndSetStatus(
                List<String> scrollbackLines, List<String> newLiveStatus) {
            scrollback.addAll(scrollbackLines);
            live = List.copyOf(newLiveStatus);
        }

        @Override public int width() { return 100; }
        @Override public int height() { return 24; }
        @Override public void flush() {}
    }
}
