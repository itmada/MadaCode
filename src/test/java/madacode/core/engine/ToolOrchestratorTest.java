package madacode.core.engine;

import madacode.core.model.*;
import madacode.core.session.*;
import madacode.core.turn.*;

import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.validation.ToolInputValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the parallel-vs-serial scheduling contract of
 * {@link ToolOrchestrator}.
 */
class ToolOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emptyInputProducesEmptyOutput() {
        ToolOrchestrator orchestrator = newOrchestrator(new ToolRegistry());
        List<ToolResult> results = orchestrator.run(
                List.of(),
                new ToolUseContext(java.nio.file.Path.of("."), new ConversationSession()));
        assertTrue(results.isEmpty());
    }

    @Test
    void preservesResultOrderAcrossMixedSegments() {
        // Ordering: safe, safe, unsafe, safe, unsafe, unsafe → 4 segments,
        // [parallel(2), serial(1), parallel(1), serial(2)]. Result order must
        // match call order regardless of execution layout.
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RecordingTool("safe", true, null));
        registry.register(new RecordingTool("unsafe", false, null));

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        List<ToolCall> calls = List.of(
                callOf("safe", "1"),
                callOf("safe", "2"),
                callOf("unsafe", "3"),
                callOf("safe", "4"),
                callOf("unsafe", "5"),
                callOf("unsafe", "6"));

        List<ToolResult> results = orchestrator.run(calls, freshContext());
        assertEquals(6, results.size());
        for (int i = 0; i < results.size(); i++) {
            assertEquals("ok-" + (i + 1), results.get(i).output(),
                    "result at index " + i + " out of order");
        }
    }

    @Test
    void concurrencySafeBatchActuallyRunsInParallel() throws Exception {
        // Three tools each block on a barrier until all three are running.
        // If they ran serially, the latch would never count to zero —
        // proves the orchestrator scheduled them concurrently.
        int parallelism = 3;
        CountDownLatch allRunning = new CountDownLatch(parallelism);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger inFlight = new AtomicInteger(0);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new RecordingTool("safe", true, () -> {
            int now = inFlight.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
            allRunning.countDown();
            try {
                if (!allRunning.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("barrier timed out — tools not parallel");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }));

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        List<ToolCall> calls = List.of(
                callOf("safe", "a"),
                callOf("safe", "b"),
                callOf("safe", "c"));
        orchestrator.run(calls, freshContext());

        assertEquals(parallelism, maxConcurrent.get(),
                "expected " + parallelism + " concurrent executions, saw " + maxConcurrent.get());
    }

    @Test
    void concurrentSegmentPublishesCompletedToolsAsTheyFinish() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastResultSeen = new CountDownLatch(1);
        List<String> resultEvents = Collections.synchronizedList(new ArrayList<>());

        ToolRegistry registry = new ToolRegistry();
        registry.register(new RecordingTool("safe", true, () -> {
            String tag = CURRENT_TAG.get();
            if ("slow".equals(tag)) {
                slowStarted.countDown();
                try {
                    if (!releaseSlow.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("slow release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if ("fast".equals(tag)) {
                try {
                    if (!slowStarted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("slow did not start");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        ConversationSession session = new ConversationSession();
        session.addListener(new SessionListener() {
            @Override
            public void onToolResultAvailable(String toolUseId, boolean success, String output) {
                resultEvents.add(toolUseId);
                if ("call-fast".equals(toolUseId)) {
                    fastResultSeen.countDown();
                }
            }
        });

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        Thread runner = new Thread(() -> orchestrator.run(
                List.of(callOf("safe", "slow"), callOf("safe", "fast")),
                new ToolUseContext(java.nio.file.Path.of("."), session)));
        runner.start();

        assertTrue(fastResultSeen.await(2, TimeUnit.SECONDS),
                "fast result should publish before slow is released");
        assertEquals(List.of("call-fast"), List.copyOf(resultEvents));

        releaseSlow.countDown();
        runner.join(2_000);
        assertFalse(runner.isAlive(), "orchestrator should finish after slow release");
    }

    @Test
    void unsafeBatchRunsStrictlySerial() {
        // For unsafe tools we record entry/exit timestamps. Any two calls'
        // intervals must be disjoint.
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        registry.register(new RecordingTool("unsafe", false, () -> {
            int now = inFlight.incrementAndGet();
            maxObserved.updateAndGet(prev -> Math.max(prev, now));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }));

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        List<ToolCall> calls = List.of(
                callOf("unsafe", "x"),
                callOf("unsafe", "y"),
                callOf("unsafe", "z"));
        orchestrator.run(calls, freshContext());
        assertEquals(1, maxObserved.get(), "unsafe tools must not overlap");
    }

    @Test
    void unknownToolProducesErrorResultWithoutCrashing() {
        ToolOrchestrator orchestrator = newOrchestrator(new ToolRegistry());
        List<ToolResult> results = orchestrator.run(
                List.of(callOf("does_not_exist", "1")),
                freshContext());
        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("unknown tool"));
    }

    @Test
    void runtimeFailureInOneCallDoesntPoisonOtherSegmentResults() {
        // First call (unsafe, throws) shouldn't prevent the safe segment
        // afterwards from completing — every result slot must be populated.
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RecordingTool("safe", true, null));
        registry.register(new RecordingTool("boom", false, () -> {
            throw new RuntimeException("kapow");
        }));

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        List<ToolCall> calls = List.of(
                callOf("boom", "1"),
                callOf("safe", "2"),
                callOf("safe", "3"));
        List<ToolResult> results = orchestrator.run(calls, freshContext());

        assertEquals(3, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("kapow"));
        assertTrue(results.get(1).success());
        assertTrue(results.get(2).success());
    }

    @Test
    void terminalLongRunningStageDoesNotSkipRemainingControllerToolsInBatch() {
        ToolRegistry registry = new ToolRegistry();
        ConversationSession session = longRunningSession(LongRunningStage.RUNNING);
        AtomicInteger afterTerminalExecutions = new AtomicInteger(0);
        registry.register(new RecordingTool("complete", true,
                () -> session.setLongRunningStage(LongRunningStage.DONE)));
        registry.register(new RecordingTool("after_terminal", false,
                afterTerminalExecutions::incrementAndGet));

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        List<ToolResult> results = orchestrator.run(
                List.of(callOf("complete", "1"), callOf("after_terminal", "2")),
                new ToolUseContext(java.nio.file.Path.of("."), session));

        assertEquals(2, results.size());
        assertTrue(results.get(0).success());
        assertTrue(results.get(1).success());
        assertEquals(1, afterTerminalExecutions.get());
    }

    @Test
    void alreadyTerminalLongRunningStageStillRunsOrdinaryControllerTools() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger(0);
        registry.register(new RecordingTool("unsafe", false, executions::incrementAndGet));
        registry.register(new RecordingTool("safe", true, executions::incrementAndGet));

        ToolOrchestrator orchestrator = newOrchestrator(registry);
        List<ToolResult> results = orchestrator.run(
                List.of(callOf("unsafe", "1"), callOf("safe", "2")),
                new ToolUseContext(java.nio.file.Path.of("."),
                        longRunningSession(LongRunningStage.DONE)));

        assertEquals(2, results.size());
        assertTrue(results.get(0).success());
        assertTrue(results.get(1).success());
        assertEquals(2, executions.get());
    }

    // -- helpers ------------------------------------------------------------

    private static ToolOrchestrator newOrchestrator(ToolRegistry registry) {
        ToolExecutor executor = new ToolExecutor(
                registry,
                new ToolInputValidator(),
                PermissionGate.permissive(),
                null);
        return new ToolOrchestrator(registry, executor);
    }

    private static ToolUseContext freshContext() {
        return new ToolUseContext(java.nio.file.Path.of("."), new ConversationSession());
    }

    private static ToolCall callOf(String toolName, String tag) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("tag", tag);
        return new ToolCall("call-" + tag, toolName, input);
    }

    private static ConversationSession longRunningSession(LongRunningStage stage) {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(stage);
        return session;
    }

    private static final ThreadLocal<String> CURRENT_TAG = new ThreadLocal<>();

    /**
     * Tool that records the {@code tag} input and runs an optional side effect
     * on each call (used to inject delays, throws, or barrier waits).
     */
    private static final class RecordingTool implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }

        private final String name;
        private final boolean concurrencySafe;
        private final Runnable hook;
        private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

        RecordingTool(String name, boolean concurrencySafe, Runnable hook) {
            this.name = name;
            this.concurrencySafe = concurrencySafe;
            this.hook = hook;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " test tool";
        }

        @Override
        public boolean isReadOnly() {
            return concurrencySafe;
        }

        @Override
        public boolean isConcurrencySafe(ObjectNode input) {
            return concurrencySafe;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode tagProp = mapper.createObjectNode();
            tagProp.put("type", "string");
            ObjectNode props = mapper.createObjectNode();
            props.set("tag", tagProp);
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", props);
            schema.set("required", mapper.createArrayNode().add("tag"));
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            String tag = input.path("tag").asText();
            seen.add(tag);
            CURRENT_TAG.set(tag);
            try {
                if (hook != null) {
                    hook.run();
                }
            } finally {
                CURRENT_TAG.remove();
            }
            return new ToolResult(name, true, "ok-" + tag);
        }
    }
}
