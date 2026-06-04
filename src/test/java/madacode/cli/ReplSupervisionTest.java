package madacode.cli;

import madacode.cli.mode.ModeRouter;
import madacode.cli.mode.ModeExecution;
import madacode.core.model.FinishReason;
import madacode.core.turn.CancellationToken;
import madacode.core.model.ContentBlock;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.engine.QueryEngine;
import madacode.core.session.SessionMode;
import madacode.core.session.SessionListener;
import madacode.core.turn.TurnHandle;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import madacode.core.engine.QueryEngineTurnRunner;
import madacode.core.session.SessionStorage;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnLog;
import madacode.permission.PermissionGate;
import madacode.permission.PermissionMode;
import madacode.prompt.SystemPromptBuilder;
import madacode.services.compact.CompactBudget;
import madacode.services.compact.CompactPlanner;
import madacode.services.compact.TokenEstimator;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.LongRunningTaskEvent;
import madacode.longrunning.LongRunningTaskStore;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 6 regression: a turn-level RT must not bring down the REPL; listener
 * exceptions must not poison delivery to other listeners or block state writes.
 */
class ReplSupervisionTest {

    @TempDir
    Path tempDir;

    @Test
    void unexpectedApiRtIsContainedAndReplContinues() throws Exception {
        // First turn: API client throws RT mid-stream. QueryEngine's own
        // catch(RuntimeException) converts it to API_ERROR (transcript carries
        // the error), and the supervisor stands as a defense-in-depth net for
        // anything QueryEngine doesn't catch. Either way: REPL must survive
        // and accept the second prompt.
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));

        AtomicInteger call = new AtomicInteger();
        ApiClient sequenced = new ApiClient() {
            @Override
            public ApiResponse send(List<Message> msgs, String sys,
                                    Collection<Tool<?>> tools, ApiStreamSink sink,
                                    CancellationToken token) {
                int n = call.incrementAndGet();
                if (n == 1) {
                    sink.onTextDelta("partial...");
                    throw new IllegalStateException("simulated unexpected RT");
                }
                sink.onTextDelta("ok now");
                return new ApiResponse("ok now", List.of());
            }
        };

        QueryEngine engine = new QueryEngine(
                sequenced, new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());

        BufferedReader reader = new BufferedReader(new StringReader("first\nsecond\nexit\n"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TurnExecutor turnExecutor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir));
        ScriptedRepl repl = new ScriptedRepl(engine, turnExecutor, session,
                reader, new PrintStream(buf, true), storage);

        repl.run();   // must return without throwing

        // Survival proof: second turn ran (would not happen if RT killed REPL).
        assertEquals(2, call.get(), "second turn did not run after first crashed");

        // Both user inputs landed in the persisted transcript.
        List<Message> messages = session.messages();
        long userCount = messages.stream()
                .filter(m -> m.role() == MessageRole.USER)
                .filter(m -> firstText(m).equals("first") || firstText(m).equals("second"))
                .count();
        assertEquals(2, userCount, "both user inputs should be persisted");
    }

    @Test
    void crashingListenerDoesNotKillReplDuringRealTurn() throws Exception {
        // End-to-end: a listener that throws RT on every fire is the exact
        // class of bug that pre-supervisor would crash the REPL on the first
        // tool fire. With listener isolation, the turn completes cleanly.
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        session.addListener(new SessionListener() {
            @Override public void onMessageAppended(int i, Message m) { throw new RuntimeException("boom"); }
            @Override public void onAssistantTextChunk(int i, String c) { throw new RuntimeException("boom"); }
            @Override public void onMetaEvent(madacode.core.model.MetaEvent meta) { throw new RuntimeException("boom"); }
            @Override public void onTurnEnd() { throw new RuntimeException("boom"); }
        });

        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    sink.onTextDelta("reply");
                    return new ApiClient.ApiResponse("reply", List.of());
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());

        BufferedReader reader = new BufferedReader(new StringReader("hi\nexit\n"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TurnExecutor turnExecutor2 = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir));
        ScriptedRepl repl = new ScriptedRepl(engine, turnExecutor2, session,
                reader, new PrintStream(buf, true), storage);

        repl.run();   // must not throw

        // Transcript still got the messages despite the broken listener.
        assertNotEquals(0, session.messages().stream()
                .filter(m -> m.role() == MessageRole.USER).count());
    }

    @Test
    void crashingListenerDoesNotBlockOtherListenersOrState() {
        ConversationSession session = new ConversationSession();
        AtomicInteger goodCalls = new AtomicInteger();

        // First listener always throws; second one counts events.
        session.addListener(new SessionListener() {
            @Override public void onMessageAppended(int index, Message message) {
                throw new RuntimeException("listener boom");
            }
        });
        session.addListener(new SessionListener() {
            @Override public void onMessageAppended(int index, Message message) {
                goodCalls.incrementAndGet();
            }
        });

        session.addMessage(Message.user("u1"));
        session.addMessage(Message.assistant("a1"));
        session.addMessage(Message.user("u2"));

        // All three writes succeeded; the well-behaved listener saw all three;
        // the broken one didn't poison anything.
        assertEquals(3, goodCalls.get(),
                "second listener should receive all 3 events despite first listener throwing");
        // Initial system + 3 messages.
        assertEquals(4, session.messages().size());
    }

    @Test
    void crashingMetaEventListenerDoesNotBlockTokenAccounting() {
        ConversationSession session = new ConversationSession();
        session.addListener(new SessionListener() {
            @Override public void onMetaEvent(madacode.core.model.MetaEvent meta) {
                throw new RuntimeException("meta-listener boom");
            }
        });

        // Even though the listener throws, the token report should still be applied
        // — token-state update happens before the fire-out loop.
        session.fireMetaEvent(new madacode.core.model.MetaEvent.TokenReport(
                new madacode.core.model.TokenUsage(7, 11, 0, 0), 0, 0));

        assertEquals(7, session.tokenUsage().inputTokens());
        assertEquals(11, session.tokenUsage().outputTokens());
    }

    @Test
    void compactSlashCommandRunsAsManagedLocalTurn() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        AtomicReference<CancellationToken> seenToken = new AtomicReference<>();

        CompactPlanner compactPlanner = new CompactPlanner(
                new TokenEstimator(),
                CompactBudget.defaults(),
                List.of(new madacode.services.compact.CompactStrategy() {
                    @Override public String name() { return "test"; }
                    @Override public Optional<madacode.services.compact.CompactResult> apply(
                            ConversationSession s, CompactBudget budget, CancellationToken token) {
                        seenToken.set(token);
                        return Optional.empty();
                    }
                }));

        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    throw new AssertionError("compact local turn must not call model turn runner");
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());

        Path turnLogDir = tempDir.resolve("turns");
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(turnLogDir));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProviderRegistry testRegistry = ProviderRegistry.singleProvider(
                new Provider("test", "test-token",
                        java.net.URI.create("https://api.anthropic.com"),
                        "claude-opus-4-7",
                        List.of(new Model("claude-opus-4-7", 200_000))));
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("/compact\nexit\n")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                testRegistry,
                compactPlanner);

        repl.run();

        assertTrue(seenToken.get() != null, "compact strategy should receive managed turn token");
        assertTrue(buf.toString().contains("Nothing compacted."));
        String turnLogs;
        try (var paths = java.nio.file.Files.walk(turnLogDir)) {
            turnLogs = paths
                    .filter(java.nio.file.Files::isRegularFile)
                    .map(path -> {
                        try {
                            return java.nio.file.Files.readString(path);
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .reduce("", String::concat);
        }
        assertTrue(turnLogs.contains("slash:/compact"));
        assertTrue(turnLogs.contains("DONE"));
    }

    @Test
    void newSlashCommandRendersFreshSessionStartWithoutSwitchNoise() {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProviderRegistry testRegistry = ProviderRegistry.singleProvider(
                new Provider("test", "test-token",
                        java.net.URI.create("https://api.anthropic.com"),
                        "claude-opus-4-7",
                        List.of(new Model("claude-opus-4-7", 200_000))));
        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    throw new AssertionError("/new must not run a model turn");
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns")));
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("/new\nexit\n")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                testRegistry,
                null);

        repl.run();

        String output = stripAnsi(buf.toString());
        assertTrue(output.contains("(saved current session)"));
        assertTrue(output.contains("MadaCode"));
        assertTrue(output.contains("provider: test"));
        assertTrue(output.contains("model: claude-opus-4-7"));
        assertTrue(output.contains("Session initialized."));
        assertTrue(!output.contains("New session:"));
        assertTrue(!output.contains("  ·  new"));
        assertTrue(!output.contains("Switched to session:"));

        int savedIndex = output.indexOf("(saved current session)");
        int welcomeIndex = output.indexOf("MadaCode", savedIndex);
        int initializedIndex = output.indexOf("Session initialized.", welcomeIndex);
        assertTrue(savedIndex >= 0);
        assertTrue(welcomeIndex > savedIndex);
        assertTrue(initializedIndex > welcomeIndex);
    }

    @Test
    void sessionModeSyncDerivesDisplayModeFromSessionAfterPlanEvents() {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPermissionMode(PermissionMode.ACCEPT_EDITS);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    throw new AssertionError("test does not run model turns");
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns")));
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("exit\n")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                null,
                null);

        session.setPlanMode(true);
        session.fireMetaEvent(new MetaEvent.PlanModeEntered());
        assertEquals(SessionMode.LONG_RUNNING, repl.sessionContext.mode());
        assertTrue(repl.sessionContext.planMode());

        session.setPlanMode(false);
        session.fireMetaEvent(new MetaEvent.PlanModeExited());
        assertEquals(SessionMode.LONG_RUNNING, repl.sessionContext.mode());
        assertTrue(repl.sessionContext.permissionMode() == PermissionMode.ACCEPT_EDITS);
    }

    @Test
    void commonModePlainInputStillAddsInputRunsTurnAndPersists() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions-common"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-common"));
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    seenPrompt.set(firstText(msgs.getLast()));
                    sink.onTextDelta("ok");
                    return new ApiClient.ApiResponse("ok", List.of());
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns-common")));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("hello mode router\nexit\n")),
                new PrintStream(buf, true),
                storage);

        repl.run();

        assertEquals(List.of("hello mode router"), session.inputHistory());
        assertEquals("hello mode router", seenPrompt.get());
        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(List.of("hello mode router"), restored.inputHistory());
        assertTrue(restored.messages().stream()
                .anyMatch(m -> m.role() == MessageRole.USER
                        && firstText(m).equals("hello mode router")));
    }

    @Test
    void longRunningModeRoutesPlainInputThroughManagedTurnAndPersists() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions-long"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-long"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    seenPrompt.set(firstText(msgs.getLast()));
                    sink.onTextDelta("executing");
                    return new ApiClient.ApiResponse("executing", List.of());
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns-long")));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("continue work\nexit\n")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                null,
                null,
                new ModeRouter(
                        new madacode.cli.mode.CommonModeHandler(executor),
                        new madacode.cli.mode.LongRunningModeHandler(executor)));

        repl.run();

        assertEquals("continue work", seenPrompt.get());
        assertEquals(List.of("continue work"), session.inputHistory());
        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(List.of("continue work"), restored.inputHistory());
        assertTrue(restored.messages().stream()
                .anyMatch(m -> m.role() == MessageRole.USER
                        && firstText(m).equals("continue work")));
    }

    @Test
    void handledSlashCommandPersistsSessionStateImmediately() {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions-slash"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-slash"));
        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    throw new AssertionError("slash mode command must not run a model turn");
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns-slash")));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProviderRegistry testRegistry = ProviderRegistry.singleProvider(
                new Provider("test", "test-token",
                        java.net.URI.create("https://api.anthropic.com"),
                        "claude-opus-4-7",
                        List.of(new Model("claude-opus-4-7", 200_000))));
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                testRegistry,
                null);

        try {
            assertTrue(repl.handleLine("/mode long-running"));
        } finally {
            executor.close();
        }

        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(SessionMode.LONG_RUNNING, restored.workflowMode());
        assertEquals(PermissionMode.BYPASS, restored.permissionMode());
        assertEquals(LongRunningStage.WAITING_FOR_TASK, restored.longRunningStage());
    }

    @Test
    void longRunContinueWithoutTaskShowsNoActiveTask() {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions-auto"));
        ConversationSession session = new ConversationSession(tempDir.resolve("ws-auto"));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        // No taskId set — command should report no active task

        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    throw new AssertionError("/longrun-continue must not run a model turn");
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns-auto")));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProviderRegistry testRegistry = ProviderRegistry.singleProvider(
                new Provider("test", "test-token",
                        java.net.URI.create("https://api.anthropic.com"),
                        "claude-opus-4-7",
                        List.of(new Model("claude-opus-4-7", 200_000))));
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("/longrun-continue 5\nexit\n")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                testRegistry,
                null);

        try {
            repl.run();
        } finally {
            executor.close();
        }

        assertTrue(stripAnsi(buf.toString()).contains("No active long-running task"));
    }

    @Test
    void approvalDoesNotAutoStartExecutingTurn() {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions-chain"));
        Path workingDirectory = tempDir.resolve("ws-chain");
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-chain", "Chain test", "planning", session.sessionId(), "WAITING_FOR_APPROVAL"));
        session.setLongRunningTaskId("task-chain");
        session.setLongRunningTaskDirectory(store.taskDirectoryPath("task-chain").toString());

        List<String> turnInputs = new ArrayList<>();
        AtomicInteger turnCount = new AtomicInteger();

        ModeRouter router = new ModeRouter(
                (line, s) -> {
                    throw new AssertionError("common handler should not be used");
                },
                (line, s) -> {
                    int turn = turnCount.incrementAndGet();
                    turnInputs.add(line);
                    TurnHandle handle = new TurnHandle(
                            "chain-" + turn,
                            CompletableFuture.completedFuture(new madacode.core.turn.TurnResult(
                                    "ok", FinishReason.COMPLETED, 1)),
                            reason -> { });
                    // Simulate approving execution: set INITIALIZING
                    return ModeExecution.managedTurn(handle, () -> {
                        s.setLongRunningStage(LongRunningStage.INITIALIZING);
                        return Optional.empty();
                    });
                });

        QueryEngine engine = new QueryEngine(
                (msgs, sys, tools, sink, tok) -> {
                    throw new AssertionError("test uses fake mode router");
                },
                new ToolRegistry(), new SystemPromptBuilder(),
                PermissionGate.permissive());
        TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(tempDir.resolve("turns-chain")));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ScriptedRepl repl = new ScriptedRepl(engine, executor, session,
                new BufferedReader(new StringReader("开始\nexit\n")),
                new PrintStream(buf, true),
                storage,
                madacode.cli.slash.SlashCommandRegistry.create(null),
                null,
                null,
                router);

        try {
            repl.run();
        } finally {
            executor.close();
        }

        assertEquals(1, turnCount.get(), "Handler should have been called once for user input");
        assertEquals("开始", turnInputs.get(0), "First turn input should be user's '开始'");
        assertEquals(LongRunningStage.INITIALIZING, session.longRunningStage());
        assertEquals(SessionMode.LONG_RUNNING, session.workflowMode());
        assertFalse(session.inputHistory().contains("[auto-start] Begin executing the approved long-running task."),
                "Auto-start input should not appear in inputHistory");
    }

    private static String firstText(Message m) {
        if (m.contentBlocks().isEmpty()) return "";
        ContentBlock first = m.contentBlocks().getFirst();
        return first instanceof ContentBlock.TextBlock tb ? tb.text() : "";
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
    }
}
