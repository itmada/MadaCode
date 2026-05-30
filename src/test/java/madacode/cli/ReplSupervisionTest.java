package madacode.cli;

import madacode.core.CancellationToken;
import madacode.core.ContentBlock;
import madacode.core.ConversationSession;
import madacode.core.Message;
import madacode.core.MessageRole;
import madacode.core.QueryEngine;
import madacode.core.SessionListener;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import madacode.core.QueryEngineTurnRunner;
import madacode.core.SessionStorage;
import madacode.core.TurnExecutor;
import madacode.core.TurnLog;
import madacode.permission.PermissionGate;
import madacode.prompt.SystemPromptBuilder;
import madacode.services.compact.CompactBudget;
import madacode.services.compact.CompactPlanner;
import madacode.services.compact.TokenEstimator;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiStreamSink;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        BufferedRepl repl = new BufferedRepl(engine, turnExecutor, session,
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
            @Override public void onMetaEvent(madacode.core.MetaEvent meta) { throw new RuntimeException("boom"); }
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
        BufferedRepl repl = new BufferedRepl(engine, turnExecutor2, session,
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
            @Override public void onMetaEvent(madacode.core.MetaEvent meta) {
                throw new RuntimeException("meta-listener boom");
            }
        });

        // Even though the listener throws, the token report should still be applied
        // — token-state update happens before the fire-out loop.
        session.fireMetaEvent(new madacode.core.MetaEvent.TokenReport(
                new madacode.core.TokenUsage(7, 11, 0, 0), 0, 0));

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
        BufferedRepl repl = new BufferedRepl(engine, executor, session,
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
        BufferedRepl repl = new BufferedRepl(engine, executor, session,
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

    private static String firstText(Message m) {
        if (m.contentBlocks().isEmpty()) return "";
        ContentBlock first = m.contentBlocks().getFirst();
        return first instanceof ContentBlock.TextBlock tb ? tb.text() : "";
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
    }
}
