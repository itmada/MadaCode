package madacode.render.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.tui.Screen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnRendererToolActivityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

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
    void explorationGroupShowsWorkspaceRelativeReadPaths() {
        Path projectFile = tempDir.resolve("backend/src/main/java/App.java");
        Harness h = new Harness(tempDir);
        ObjectNode read = input("path", projectFile.toString());

        h.appendTool("r1", "file_read", read);
        h.complete("r1", "file_read", read, true, "class App {}");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertTrue(hasLine(lines, "Read backend/src/main/java/App.java"));
        assertTrue(lines.stream().noneMatch(line -> line.contains(projectFile.toString())),
                "cwd-contained absolute input should be display-only relativized");
    }

    @Test
    void explorationGroupKeepsOutsideReadPathsAbsolute() {
        Path outside = tempDir.getParent().resolve("outside/App.java").toAbsolutePath().normalize();
        Harness h = new Harness(tempDir);
        ObjectNode read = input("path", outside.toString());

        h.appendTool("r1", "file_read", read);
        h.complete("r1", "file_read", read, true, "class App {}");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertTrue(hasLine(lines, "Read " + outside));
    }

    @Test
    void consecutiveReadsCollapseInsideExplorationGroup() {
        Harness h = new Harness();
        ObjectNode first = input("path", "/tmp/a.txt");
        ObjectNode second = input("path", "/tmp/b.txt");
        ObjectNode third = input("path", "/tmp/c.txt");

        h.appendTool("r1", "file_read", first);
        h.appendTool("r2", "file_read", second);
        h.appendTool("r3", "file_read", third);
        h.complete("r1", "file_read", first, true, "a");
        h.complete("r2", "file_read", second, true, "b");
        h.complete("r3", "file_read", third, true, "c");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertTrue(hasLine(lines, "Read · 3 files · 21ms"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("/tmp/a.txt")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("/tmp/b.txt")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("/tmp/c.txt")));
    }

    @Test
    void consecutiveReadsCollapseWhileStillRunning() {
        Harness h = new Harness();
        ObjectNode first = input("path", "/tmp/a.txt");
        ObjectNode second = input("path", "/tmp/b.txt");
        ObjectNode third = input("path", "/tmp/c.txt");

        h.appendTool("r1", "file_read", first);
        h.appendTool("r2", "file_read", second);
        h.appendTool("r3", "file_read", third);
        h.renderer.onToolExecutionStarted("r1", "file_read", first);
        h.view.flushNow();

        List<String> live = strip(h.screen.live);
        assertTrue(hasLine(live, "Read · 3 files · reading"));
        assertTrue(live.stream().noneMatch(line -> line.contains("/tmp/a.txt")));
        assertTrue(live.stream().noneMatch(line -> line.contains("/tmp/b.txt")));
        assertTrue(live.stream().noneMatch(line -> line.contains("/tmp/c.txt")));
    }

    @Test
    void readOnlyBashDisplaysAsInspectionNotFileRead() {
        Harness h = new Harness();
        ObjectNode bash = input("command", "cat backend/pom.xml");

        h.appendTool("b1", "bash", bash);
        h.complete("b1", "bash", bash, true, "<project/>");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertTrue(hasLine(lines, "Inspect cat backend/pom.xml"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("Read cat backend/pom.xml")));
    }

    @Test
    void explorationGroupSpansCompletedToolBatchesUntilSemanticBoundary() {
        Harness h = new Harness();
        ObjectNode firstRead = input("path", "/tmp/README.md");
        ObjectNode firstList = input("command",
                "echo \"=== Source files ===\" && find src/main/resources -type f | head -50");
        ObjectNode secondRead = input("path", "/tmp/pom.xml");
        ObjectNode secondSearch = input("pattern", "Controller").put("path", "src");

        h.appendTool("r1", "file_read", firstRead);
        h.appendTool("b1", "bash", firstList);
        h.complete("r1", "file_read", firstRead, true, "a");
        h.complete("b1", "bash", firstList, true, "README.md");

        h.appendTool("r2", "file_read", secondRead);
        h.appendTool("s1", "grep", secondSearch);
        h.complete("r2", "file_read", secondRead, true, "b");
        h.complete("s1", "grep", secondSearch, true, "src/App.java:1:Controller");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertEquals(1, count(lines, "Explored"), "completed exploration batches should remain one group");
        assertTrue(hasLine(lines, "Read /tmp/README.md"));
        assertTrue(hasLine(lines, "List echo \"=== Source files ===\" && find src/main/resources -type f | head -50"));
        assertTrue(hasLine(lines, "Read /tmp/pom.xml"));
        assertTrue(hasLine(lines, "Search \"Controller\" in src"));
    }

    @Test
    void standaloneToolBreaksExplorationGroup() {
        Harness h = new Harness();
        ObjectNode firstRead = input("path", "/tmp/a.txt");
        ObjectNode edit = input("file_path", "/tmp/a.txt");
        ObjectNode secondRead = input("path", "/tmp/b.txt");

        h.appendTool("r1", "file_read", firstRead);
        h.complete("r1", "file_read", firstRead, true, "a");
        h.appendTool("e1", "file_edit", edit);
        h.complete("e1", "file_edit", edit, true, "File updated successfully");
        h.appendTool("r2", "file_read", secondRead);
        h.complete("r2", "file_read", secondRead, true, "b");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertEquals(2, count(lines, "Explored"), "edit should break exploration grouping");
        assertTrue(hasLine(lines, "file_edit /tmp/a.txt"), "file_edit remains an independent card");
    }

    @Test
    void mutatingBashBreaksExplorationGroup() {
        Harness h = new Harness();
        ObjectNode firstRead = input("path", "/tmp/a.txt");
        ObjectNode mutating = input("command", "touch generated.txt && ls");
        ObjectNode secondRead = input("path", "/tmp/b.txt");

        h.appendTool("r1", "file_read", firstRead);
        h.complete("r1", "file_read", firstRead, true, "a");
        h.appendTool("b1", "bash", mutating);
        h.complete("b1", "bash", mutating, true, "generated.txt");
        h.appendTool("r2", "file_read", secondRead);
        h.complete("r2", "file_read", secondRead, true, "b");

        h.renderer.onTurnEnd();

        List<String> lines = strip(h.screen.scrollback);
        assertEquals(2, count(lines, "Explored"), "mutating bash should be a semantic boundary");
        assertTrue(hasLine(lines, "bash touch generated.txt && ls"));
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

    @Test
    void assistantTextClosesCompletedExplorationGroupBeforeStreaming() {
        Harness h = new Harness();
        ObjectNode firstRead = input("path", "/tmp/a.txt");
        ObjectNode secondRead = input("path", "/tmp/b.txt");

        h.appendTool("r1", "file_read", firstRead);
        h.complete("r1", "file_read", firstRead, true, "a");
        h.appendTool("r2", "file_read", secondRead);
        h.complete("r2", "file_read", secondRead, true, "b");

        h.renderer.onAssistantTextChunk(0,
                "分析完成后开始正式输出正文。这里有足够长的内容，"
                        + "用于确认已经完成的探索组不会继续占住 live 区并阻塞正文流式输出。");
        h.view.flushNow();

        List<String> scrollback = strip(h.screen.scrollback);
        assertEquals(1, count(scrollback, "Explored"),
                "completed exploration should spill to scrollback when assistant text starts");
        assertTrue(hasLine(scrollback, "Read · 2 files · 14ms"));
        assertTrue(scrollback.stream().noneMatch(line -> line.contains("/tmp/a.txt")));
        assertTrue(scrollback.stream().noneMatch(line -> line.contains("/tmp/b.txt")));

        List<String> live = strip(h.screen.live);
        assertTrue(String.join("", live).contains("正式输出正文"),
                "assistant text should own live preview after exploration closes");
        assertTrue(live.stream().noneMatch(line -> line.contains("Explored")),
                "closed exploration group should no longer remain in live");
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
        final TurnRenderer renderer;

        Harness() {
            this(null);
        }

        Harness(Path workingDirectory) {
            renderer = new TurnRenderer(view, screen, () -> workingDirectory);
        }

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
