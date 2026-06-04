package madacode.longrunning;

import madacode.core.engine.QueryEngine;
import madacode.core.engine.QueryEngineTurnRunner;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.session.SessionStorage;
import madacode.core.turn.TurnExecutor;
import madacode.core.turn.TurnHandle;
import madacode.core.turn.TurnLog;
import madacode.core.turn.TurnResult;
import madacode.permission.PermissionMode;
import madacode.prompt.SystemPromptBuilder;
import madacode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates and runs a fresh worker agent session for a single bounded work cycle.
 *
 * <p>The worker session is independent from the control session — it does not
 * inherit conversation messages. The worker reads the task store, performs one
 * bounded unit of work, and calls {@code worker_report} before ending.
 */
public class LongRunningWorkerRunner {

    private final QueryEngineFactory queryEngineFactory;
    private final SessionStorage sessionStorage;
    private final ToolRegistry toolRegistry;
    private final Path turnLogRoot;

    /**
     * Creates a worker runner with the given dependencies.
     *
     * @param queryEngineFactory  factory to create a QueryEngine for the worker session
     * @param sessionStorage      storage to persist the worker session after the turn
     * @param toolRegistry        full tool registry (policy will filter for worker)
     */
    public LongRunningWorkerRunner(
            QueryEngineFactory queryEngineFactory,
            SessionStorage sessionStorage,
            ToolRegistry toolRegistry,
            Path turnLogRoot) {
        this.queryEngineFactory = Objects.requireNonNull(queryEngineFactory, "queryEngineFactory");
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.turnLogRoot = Objects.requireNonNull(turnLogRoot, "turnLogRoot");
    }

    /**
     * Factory for creating {@link QueryEngine} instances with a specific prompt builder.
     */
    @FunctionalInterface
    public interface QueryEngineFactory {
        QueryEngine create(ToolRegistry toolRegistry, SystemPromptBuilder promptBuilder);
    }

    /**
     * Runs a single bounded worker cycle for the given task.
     *
     * @param taskId        the task to work on
     * @param projectDir    the project working directory
     * @return the result of the worker run
     */
    public WorkerRunResult run(String taskId, Path projectDir) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(projectDir, "projectDir");

        LongRunningTaskStore store = new LongRunningTaskStore(projectDir);
        Path taskDir = store.taskDirectoryPath(taskId);

        // Create a fresh worker session
        ConversationSession workerSession = new ConversationSession(projectDir);
        workerSession.setWorkflowMode(SessionMode.LONG_RUNNING);
        workerSession.setLongRunningStage(LongRunningStage.RUNNING);
        workerSession.setLongRunningWorkerSession(true);
        workerSession.setLongRunningTaskId(taskId);
        workerSession.setLongRunningTaskDirectory(taskDir.toString());
        workerSession.setPermissionMode(PermissionMode.LONG_RUNNING_WORKSPACE);

        // Build worker-specific system prompt
        String workerPrompt = LongRunningWorkerPrompt.build();
        SystemPromptBuilder workerPromptBuilder = new SystemPromptBuilder(workerPrompt);

        // Create a QueryEngine for the worker
        QueryEngine workerEngine = queryEngineFactory.create(
                toolRegistry, workerPromptBuilder);

        // Run the worker turn through the managed turn executor so worker
        // sessions get the same turn log and terminal-state accounting as
        // foreground turns, without attaching foreground UI listeners.
        String workerInput = "[worker-start] Execute exactly one long-running worker cycle for task " + taskId + ".";
        TurnResult turnResult;
        try (TurnExecutor workerExecutor = new TurnExecutor(
                new QueryEngineTurnRunner(workerEngine),
                new TurnLog(turnLogRoot))) {
            TurnHandle handle = workerExecutor.submit(workerSession, workerInput);
            try {
                turnResult = waitForWorkerTurn(handle);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
            } catch (InterruptedException e) {
                handle.cancel().accept("long-running launcher interrupted");
                Thread.currentThread().interrupt();
                throw new RuntimeException("Worker interrupted", e);
            }
        }

        // Save the worker session
        try {
            sessionStorage.save(workerSession);
        } catch (RuntimeException e) {
            try {
                store.appendEvent(taskId, LongRunningTaskEvent.of(
                        "worker_session_save_failed",
                        taskId,
                        workerSession.sessionId(),
                        workerSession.longRunningStage().name(),
                        null,
                        false,
                        "Failed to save worker session: " + e.getMessage(),
                        Map.of("workerSessionId", workerSession.sessionId())));
            } catch (RuntimeException ignored) {
                // Worker report remains the source of launcher control flow.
            }
        }

        // Read the report
        Optional<WorkerReport> report = workerSession.lastWorkerReport();

        return new WorkerRunResult(
                workerSession.sessionId(),
                turnResult,
                report);
    }

    private static TurnResult waitForWorkerTurn(TurnHandle handle)
            throws InterruptedException, ExecutionException {
        while (true) {
            try {
                return handle.result().get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                if (Thread.currentThread().isInterrupted()) {
                    handle.cancel().accept("long-running launcher interrupted");
                    throw new InterruptedException("long-running launcher interrupted");
                }
            }
        }
    }

    /**
     * Result of a single worker run.
     */
    public record WorkerRunResult(
            String workerSessionId,
            TurnResult turnResult,
            Optional<WorkerReport> report
    ) {}
}
