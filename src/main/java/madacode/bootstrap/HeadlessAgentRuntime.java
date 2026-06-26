package madacode.bootstrap;

import madacode.cli.CliArgs;
import madacode.core.engine.QueryEngine;
import madacode.core.engine.QueryEngineTurnRunner;
import madacode.core.session.SessionStorage;
import madacode.core.session.ConversationSession;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnHandle;
import madacode.core.turn.TurnLog;
import madacode.core.turn.TurnResult;
import madacode.events.AppEventPublisher;
import madacode.longrunning.LongRunningWorkerRunner;
import madacode.permission.ApprovalResponse;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;
import madacode.services.api.ApiClient;
import madacode.storage.RuntimePaths;
import madacode.tool.ToolRegistry;
import madacode.tool.access.ToolAccessResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.function.Consumer;

/**
 * A headless assembly of the full MadaCode agent — the same real object graph the
 * interactive CLI boots, minus the TUI. Lives in the {@code bootstrap} package so it can
 * reuse the package-private {@link EnvironmentAssembly} and {@link ToolAssembly}; this is
 * the single seam between the eval framework and bootstrap internals, so eval code never
 * reaches into bootstrap and there is no tool drift from production.
 *
 * <p>What it gives you: the real streaming {@link ApiClient}, the full tool registry,
 * the real {@link DefaultPermissionGate} rule chain, and bounded
 * {@link LongRunningWorkerRunner} factories wired like {@code LongRunningReplCoordinator}.
 * Callers supply an execution-environment session
 * per run; this runtime is built once and reused across cases.
 *
 * <p>Permission behavior is driven entirely by each session's {@code PermissionMode}: the
 * gate uses an auto-DENY approval prompt, so BYPASS auto-allows everything (full autonomy),
 * ACCEPT_EDITS allows only edits, and DEFAULT effectively runs read-only.
 *
 * <p>Requires {@code ~/.mada/providers.json} to be configured (eval connects a real model).
 */
public final class HeadlessAgentRuntime implements AutoCloseable {

    private final EnvironmentRuntime env;
    private final ToolRuntime tools;
    private final PermissionGate gate;
    private final BootstrapResources resources;
    private final SessionStorage sessionStorage;
    private final Path turnLogRoot;
    private final Path sessionsDir;
    private final AppEventPublisher publisher;

    private HeadlessAgentRuntime(
            EnvironmentRuntime env,
            ToolRuntime tools,
            PermissionGate gate,
            BootstrapResources resources,
            SessionStorage sessionStorage,
            Path turnLogRoot,
            Path sessionsDir,
            AppEventPublisher publisher) {
        this.env = env;
        this.tools = tools;
        this.gate = gate;
        this.resources = resources;
        this.sessionStorage = sessionStorage;
        this.turnLogRoot = turnLogRoot;
        this.sessionsDir = sessionsDir;
        this.publisher = publisher;
    }

    /**
     * Builds the full headless runtime rooted at {@code projectDir}. Per-case sandboxes are
     * supplied later via the session's working directory; {@code projectDir} only anchors
     * provider/path resolution.
     */
    public static HeadlessAgentRuntime create(Path projectDir) {
        Path home = Path.of(System.getProperty("user.home"));
        RuntimePaths paths = RuntimePaths.forProject(home, projectDir.toAbsolutePath().normalize());
        if (!Files.isRegularFile(paths.globalProvidersFile())) {
            throw new IllegalStateException(
                    "eval requires a configured provider, but none was found at "
                            + paths.globalProvidersFile()
                            + " — run `mada` once to set up a provider first.");
        }

        AppEventPublisher publisher = new HeadlessEventPublisher();
        BootstrapResources resources = new BootstrapResources();
        Path turnLogRoot = null;
        Path sessionsDir = null;
        try {
            CliArgs args = new CliArgs.NewSession(true, false, null);
            TerminalRuntime terminal = new TerminalRuntime(null, null, null, null);

            EnvironmentRuntime env = EnvironmentAssembly.create(args, terminal, paths, publisher);
            PermissionGate gate = denyPromptGate(env, publisher);
            ToolRuntime tools = ToolAssembly.create(env, resources, gate, publisher);

            // Sessions persist to a throwaway temp dir so eval runs (notably long-running
            // worker sessions, which save themselves) never pollute the user's real
            // ~/.mada session history.
            sessionsDir = Files.createTempDirectory("mada-eval-sessions-");
            SessionStorage sessionStorage = new SessionStorage(sessionsDir);
            turnLogRoot = Files.createTempDirectory("mada-eval-worklog-");

            return new HeadlessAgentRuntime(
                    env, tools, gate, resources, sessionStorage,
                    turnLogRoot, sessionsDir, publisher);
        } catch (IOException e) {
            cleanup(resources, publisher, turnLogRoot, sessionsDir);
            throw new UncheckedIOException("failed to assemble headless runtime", e);
        } catch (RuntimeException | Error e) {
            cleanup(resources, publisher, turnLogRoot, sessionsDir);
            throw e;
        }
    }

    /** Releases partially-built resources when {@link #create} fails midway. */
    private static void cleanup(
            BootstrapResources resources, AppEventPublisher publisher, Path turnLogRoot, Path sessionsDir) {
        try {
            resources.close();
        } catch (Exception ignored) {
            // best-effort
        }
        publisher.close();
        deleteTree(turnLogRoot);
        deleteTree(sessionsDir);
    }

    /** Real rule-chain gate; the DENY prompt is only reached when no rule auto-decides. */
    private static PermissionGate denyPromptGate(
            EnvironmentRuntime environment,
            AppEventPublisher publisher) {
        return new DefaultPermissionGate(
                (tool, input) -> ApprovalResponse.DENY,
                java.util.List.of(),
                environment.diagnosticEvents(),
                publisher);
    }

    /**
     * Builds a fresh single-turn engine (common/plan modes) using the exact same production
     * configuration as {@link EngineAssembly} (skills in the prompt, context compaction,
     * diagnostics, tool-access policy), with two deliberate eval deviations: a
     * {@code maxIterations} cap for cost safety (production is unbounded) and a {@code null}
     * memory loader so runs are reproducible. Cheap to build per case; the expensive
     * provider/tool graph is shared.
     */
    public QueryEngine newEngine(int maxIterations) {
        return EngineAssembly.configuredBuilder(
                        env, tools, gate, null, EngineAssembly.createCompaction(env))
                .maxIterations(maxIterations)
                .build();
    }

    /** Creates a worker runner whose model loop is explicitly bounded for an eval case. */
    public LongRunningWorkerRunner newWorkerRunner(int maxIterations) {
        return newWorkerRunner(maxIterations, session -> {}, session -> {});
    }

    /**
     * Creates a bounded worker runner and reports each completed worker session to an
     * attempt-scoped observer. Production callers use the no-op overload.
     */
    public LongRunningWorkerRunner newWorkerRunner(
            int maxIterations,
            Consumer<ConversationSession> completedSessionObserver) {
        return newWorkerRunner(maxIterations, completedSessionObserver, session -> {});
    }

    /**
     * Creates a bounded worker runner that reports each completed worker session and
     * installs {@code subAgentSpawnObserver} on every worker session, so sub-agents
     * spawned by a worker (and their descendants) are observable to an attempt-scoped
     * collector. Production callers use a no-op overload.
     */
    public LongRunningWorkerRunner newWorkerRunner(
            int maxIterations,
            Consumer<ConversationSession> completedSessionObserver,
            Consumer<ConversationSession> subAgentSpawnObserver) {
        LongRunningWorkerRunner.QueryEngineFactory workerFactory = (registry, promptBuilder) ->
                QueryEngine.builder(
                                env.api(), registry, promptBuilder,
                                denyPromptGate(env, publisher))
                        .toolAccessResolver(tools.toolAccessResolver())
                        .maxIterations(maxIterations)
                        .build();
        return new LongRunningWorkerRunner(
                workerFactory,
                sessionStorage,
                tools.registry(),
                turnLogRoot,
                completedSessionObserver,
                subAgentSpawnObserver);
    }

    /**
     * Runs a headless turn through the production managed-turn path with cooperative
     * cancellation and a hard caller-visible timeout.
     */
    public TurnResult runTurn(
            QueryEngine engine,
            ConversationSession session,
            String instruction,
            Duration timeout) {
        try (TurnExecutor executor = new TurnExecutor(
                new QueryEngineTurnRunner(engine), new TurnLog(turnLogRoot))) {
            TurnHandle handle = executor.submit(session, instruction);
            try {
                return handle.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                handle.cancel().accept("eval case timeout");
                boolean quiescent = awaitTurnStop(handle);
                throw new HeadlessTurnTimeoutException(
                        "agent turn timed out after " + timeout, quiescent);
            } catch (InterruptedException e) {
                handle.cancel().accept("eval launcher interrupted");
                boolean quiescent = awaitTurnStop(handle);
                Thread.currentThread().interrupt();
                throw new HeadlessTurnTimeoutException(
                        "agent turn interrupted", e, quiescent);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw cause instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(cause);
            }
        }
    }

    public ApiClient api() {
        return env.api();
    }

    public ToolRegistry toolRegistry() {
        return tools.registry();
    }

    public ToolAccessResolver toolAccessResolver() {
        return tools.toolAccessResolver();
    }

    public PermissionGate permissionGate() {
        return gate;
    }

    public String providerName() {
        return env.providerRegistry().active().provider().name();
    }

    public String modelName() {
        return env.providerRegistry().active().currentModel().name();
    }

    public Path projectDir() {
        return env.projectDir();
    }

    /** Hashes the effective model-facing extension/tool profile without including credentials. */
    public String runtimeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, providerName());
            updateDigest(digest, modelName());
            tools.registry().tools().stream()
                    .map(tool -> tool.name())
                    .sorted()
                    .forEach(value -> updateDigest(digest, "tool:" + value));
            tools.skillRegistry().enabled().stream()
                    .sorted(Comparator.comparing(madacode.skill.Skill::name))
                    .forEach(skill -> updateDigest(
                            digest, "skill:" + skill.name() + ":" + skill.body()));
            tools.agentRegistry().all().stream()
                    .sorted(Comparator.comparing(madacode.agent.AgentDefinition::agentType))
                    .forEach(agent -> updateDigest(
                            digest, "agent:" + agent.agentType() + ":" + agent.systemPrompt()));
            tools.mcpManager().allServers().stream()
                    .sorted(Comparator.comparing(madacode.mcp.McpServer::name))
                    .forEach(server -> updateDigest(
                            digest, "mcp:" + server.name() + ":" + server.status()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public SessionStorage sessionStorage() {
        return sessionStorage;
    }

    @Override
    public void close() {
        cleanup(resources, publisher, turnLogRoot, sessionsDir);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static boolean awaitTurnStop(TurnHandle handle) {
        try {
            handle.result().get(10, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static final class HeadlessTurnTimeoutException extends RuntimeException {
        private final boolean quiescent;

        public HeadlessTurnTimeoutException(String message, boolean quiescent) {
            super(message);
            this.quiescent = quiescent;
        }

        public HeadlessTurnTimeoutException(
                String message,
                Throwable cause,
                boolean quiescent) {
            super(message, cause);
            this.quiescent = quiescent;
        }

        public boolean quiescent() {
            return quiescent;
        }
    }
}
