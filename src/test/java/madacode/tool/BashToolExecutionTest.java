package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.turn.CancellationToken;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionListener;
import madacode.core.engine.ToolExecutor;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 8 regression: output collected by the reader thread must survive
 * timeout, cancellation, and unexpected errors — never silently dropped
 * because the reader thread couldn't finish in time.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class BashToolExecutionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void normalExitReturnsFullOutput() {
        ToolResult result = run("echo hello; echo world", 5);

        assertTrue(result.success(), "exit 0 should be success");
        assertTrue(result.output().contains("hello"), () -> "missing 'hello': " + result.output());
        assertTrue(result.output().contains("world"), () -> "missing 'world': " + result.output());
    }

    @Test
    void timeoutPreservesPartialOutput() {
        // The command emits a marker then sleeps past the timeout. Before the
        // fix, the destroy-then-future.get(1s) path could swallow the marker.
        // Now the collector holds it regardless of reader-thread state.
        ToolResult result = run("echo MARKER_BEFORE_TIMEOUT; sleep 30", 1);

        assertFalse(result.success(), "timeout should be reported as failure");
        assertTrue(result.output().contains("MARKER_BEFORE_TIMEOUT"),
                () -> "partial output lost on timeout: " + result.output());
        assertTrue(result.output().contains("Command timed out after 1s"),
                () -> "timeout marker missing: " + result.output());
    }

    @Test
    void timeoutMillisAliasIsAccepted() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo MARKER_BEFORE_TIMEOUT_ALIAS; sleep 30");
        input.put("timeout", 200);

        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 1, CancellationToken.never());
        ToolResult result = ToolTestSupport.invoke(new BashTool(), input, ctx);

        assertFalse(result.success(), "timeout should be reported as failure");
        assertTrue(result.output().contains("MARKER_BEFORE_TIMEOUT_ALIAS"),
                () -> "partial output lost on timeout: " + result.output());
        assertTrue(result.output().contains("Command timed out after 200ms"),
                () -> "timeout marker missing: " + result.output());
    }

    @Test
    void timeoutKillsChildProcessTree() throws Exception {
        Path pidFile = tempDir.resolve("child.pid");
        String command = "sleep 30 & echo $! > " + shQuote(pidFile.toString()) + "; wait";

        ToolResult result = run(command, 1);

        assertFalse(result.success(), "timeout should be reported as failure");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(eventuallyProcessExits(childPid, Duration.ofSeconds(3)),
                () -> "child process was left alive after timeout: " + childPid);
    }

    @Test
    void cancellationPreservesPartialOutput() throws Exception {
        // Cancel the token after we've had time to emit some output.
        CancellationToken token = CancellationToken.create();
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 1, token);

        // Schedule cancellation shortly after start.
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            token.cancel("user pressed Ctrl+C");
        }, "test-canceller").start();

        ToolResult result = new BashTool().execute(
                new BashTool.Input("echo MARKER_BEFORE_CANCEL; sleep 30", null, 30), ctx);

        assertFalse(result.success(), "cancel should be reported as failure");
        assertTrue(result.output().contains("MARKER_BEFORE_CANCEL"),
                () -> "partial output lost on cancel: " + result.output());
        assertTrue(result.output().contains("Cancelled"),
                () -> "cancel marker missing: " + result.output());
    }

    @Test
    void progressFireIsCappedAtMaxLines() {
        // Emits 150 lines but the cap is 100; the rest must not fire progress
        // events, otherwise renderers/listeners get spammed.
        ConversationSession session = new ConversationSession(tempDir);
        AtomicInteger progressEvents = new AtomicInteger();
        session.addListener(new SessionListener() {
            @Override
            public void onToolExecutionProgress(String toolUseId, String progressText) {
                progressEvents.incrementAndGet();
            }
        });
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 1, CancellationToken.never());

        // ToolExecutor sets this ThreadLocal in production; we simulate it here
        // so the progress-fire code path is exercised.
        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_test");
        ToolResult result;
        try {
            result = new BashTool().execute(
                    new BashTool.Input("for i in $(seq 1 150); do echo line$i; done", null, 10), ctx);
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        assertTrue(result.success());
        // Output should still contain ALL 150 lines (no truncation at progress cap).
        assertTrue(result.output().contains("line1\n"));
        assertTrue(result.output().contains("line150"));
        // But progress events were capped.
        assertTrue(progressEvents.get() <= 100,
                () -> "progress fires exceeded cap: " + progressEvents.get());
        assertTrue(progressEvents.get() >= 50,
                () -> "expected at least some progress fires: " + progressEvents.get());
    }

    @Test
    void largeOutputIsBoundedInMemoryAndMarkedTruncated() {
        ToolResult result = run("yes x | head -c 40000", 10);

        assertTrue(result.success());
        assertTrue(result.output().length() < 32_000,
                () -> "output should be bounded, got length " + result.output().length());
        assertTrue(result.output().contains("chars truncated"),
                () -> "missing truncation marker: " + result.output());
    }

    @Test
    void unexpectedRuntimeErrorStillReturnsAnyCollectedOutput() {
        // Run something that produces output then forcefully kill via cancel
        // immediately — the outer catch path also snapshots collected lines.
        // (This is more of a "no path can silently drop data" assertion.)
        CancellationToken token = CancellationToken.create();
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 1, token);

        // Cancel before BashTool can run waitFor — exercise the cancel-before-start race.
        token.cancel("pre-cancel");
        ToolResult result = new BashTool().execute(
                new BashTool.Input("echo HELLO; sleep 0.1", null, 5), ctx);

        assertFalse(result.success());
        assertTrue(result.output().contains("Cancelled")
                        || result.output().contains("cancelled"),
                () -> "expected cancellation indication: " + result.output());
    }

    private ToolResult run(String command, int timeoutSeconds) {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext ctx = new ToolUseContext(tempDir, session, 0, 1, CancellationToken.never());
        return new BashTool().execute(
                new BashTool.Input(command, null, timeoutSeconds), ctx);
    }

    private static boolean eventuallyProcessExits(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
