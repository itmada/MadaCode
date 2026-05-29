package madacode.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolUseContextTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultConstructorSetsDepthZeroAndMaxDepthOne() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session);

        assertEquals(0, ctx.depth());
        assertEquals(1, ctx.maxDepth());
    }

    @Test
    void fullConstructorPreservesValues() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 2, 5);

        assertEquals(2, ctx.depth());
        assertEquals(5, ctx.maxDepth());
        assertEquals(tempDir, ctx.workingDirectory());
        assertSame(session, ctx.session());
    }

    @Test
    void canSpawnSubAgentReturnsTrueWhenDepthBelowMaxDepth() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 3);

        assertTrue(ctx.canSpawnSubAgent());
    }

    @Test
    void canSpawnSubAgentReturnsFalseWhenDepthEqualsMaxDepth() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 2, 2);

        assertFalse(ctx.canSpawnSubAgent());
    }

    @Test
    void canSpawnSubAgentReturnsFalseWhenDepthExceedsMaxDepth() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 5, 3);

        assertFalse(ctx.canSpawnSubAgent());
    }

    @Test
    void defaultContextCanSpawnOneSubAgent() {
        ToolUseContext ctx = new ToolUseContext(tempDir, new ConversationSession(tempDir));

        assertTrue(ctx.canSpawnSubAgent());
    }

    @Test
    void childContextIncrementsDepthAndKeepsMaxDepthAndWorkingDirectory() {
        ConversationSession parentSession = new ConversationSession(tempDir);
        ToolUseContext parent = new ToolUseContext(tempDir, parentSession, 2, 5);
        ConversationSession childSession = new ConversationSession(tempDir);

        ToolUseContext child = parent.childContext(childSession);

        assertEquals(3, child.depth());
        assertEquals(5, child.maxDepth());
        assertEquals(tempDir, child.workingDirectory());
        assertSame(childSession, child.session());
    }

    @Test
    void childContextPreservesWorkingDirectoryNotChildSessionDirectory() {
        ConversationSession parentSession = new ConversationSession(tempDir);
        ToolUseContext parent = new ToolUseContext(tempDir, parentSession, 0, 2);
        ConversationSession childSession = new ConversationSession(Path.of("/other/dir"));

        ToolUseContext child = parent.childContext(childSession);

        assertEquals(tempDir, child.workingDirectory());
    }

    @Test
    void negativeDepthThrowsIllegalArgumentException() {
        ConversationSession session = new ConversationSession(tempDir);
        assertThrows(IllegalArgumentException.class, () ->
                new ToolUseContext(tempDir, session, -1, 5));
    }

    @Test
    void negativeMaxDepthThrowsIllegalArgumentException() {
        ConversationSession session = new ConversationSession(tempDir);
        assertThrows(IllegalArgumentException.class, () ->
                new ToolUseContext(tempDir, session, 0, -1));
    }

    @Test
    void depthChainCanSpawnUntilMaxDepthReached() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 3);

        assertTrue(ctx.canSpawnSubAgent());
        ToolUseContext child1 = ctx.childContext(new ConversationSession(tempDir));
        assertTrue(child1.canSpawnSubAgent());
        ToolUseContext child2 = child1.childContext(new ConversationSession(tempDir));
        assertTrue(child2.canSpawnSubAgent());
        ToolUseContext child3 = child2.childContext(new ConversationSession(tempDir));
        assertFalse(child3.canSpawnSubAgent());
    }
}
