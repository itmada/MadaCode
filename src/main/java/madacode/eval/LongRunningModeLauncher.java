package madacode.eval;

import madacode.core.engine.QueryEngine;
import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnResult;
import madacode.longrunning.LongRunningLauncher;
import madacode.longrunning.LongRunningController;
import madacode.longrunning.LongRunningTaskContext;
import madacode.longrunning.LongRunningTaskInitializer;
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.LongRunningTransitions;
import madacode.core.session.LongRunningStage;

import java.nio.file.Path;

/**
 * Long-running mode launcher: drives the real autonomous worker lifecycle for large tasks.
 *
 * <p>Flow, all on the production components:
 * <ol>
 *   <li>Seed durable DRAFT state via {@link LongRunningTaskInitializer#ensurePlanningTask}.</li>
 *   <li>Run one planning turn through {@link QueryEngine#runTurn} so the model builds the
 *       feature/issue list the launcher's worker-cycle budget is derived from.</li>
 *   <li>Drive bounded worker cycles via {@link LongRunningLauncher#run}, which transitions
 *       the task to execution and loops {@code workerRunner.run} until terminal or the cycle
 *       cap ({@code maxCycles}).</li>
 * </ol>
 *
 * <p>Each worker cycle runs in its own session. Worker token and tool metrics are returned
 * through {@link LongRunningLauncher.LaunchResult} and aggregated with the planning session.
 * Final pass/fail is judged objectively by the case's verify.sh.
 */
public final class LongRunningModeLauncher implements ModeLauncher {

    @Override
    public String modeId() {
        return "long-running";
    }

    @Override
    public LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context) {
        Path dir = session.workingDirectory();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);

        LongRunningTaskStore store = new LongRunningTaskStore(dir);
        LongRunningTaskInitializer initializer = new LongRunningTaskInitializer(
                store, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
        LongRunningTaskContext task = initializer.ensurePlanningTask(session, evalCase.instruction());

        // The production lifecycle requires DRAFT planning to populate feature_list.json
        // before the task may transition to RUNNING.
        QueryEngine engine = context.runtime().newEngine(context.budget().maxIterations());
        TurnResult planning;
        try {
            planning = context.runtime().runTurn(
                    engine, session, evalCase.instruction(), context.remainingTime());
        } catch (madacode.bootstrap.HeadlessAgentRuntime.HeadlessTurnTimeoutException e) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.TIMED_OUT,
                    RunMetrics.fromSession(session, 0),
                    "plan=TIMED_OUT",
                    e.getMessage(),
                    e.quiescent());
        }
        RunMetrics planningMetrics = RunMetrics.fromSession(session, planning.iterations());
        if (context.traceCollector() != null) {
            context.traceCollector().recordSession(session, ToolInvocation.Phase.CONTROL);
        }
        if (planning.finishReason() != FinishReason.COMPLETED) {
            return new LaunchOutcome(
                    executionStatus(planning.finishReason()),
                    planningMetrics,
                    "plan=" + planning.finishReason().name(),
                    planning.finalText());
        }
        if (store.readFeatureList(task.taskId()).isEmpty()) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.WORKFLOW_FAILED,
                    planningMetrics,
                    "plan=COMPLETED launch=NOT_STARTED",
                    "planning completed without producing a feature list");
        }

        // Apply the same controller-owned transition used by the interactive runtime.
        // This keeps validation and lifecycle persistence behind one production authority.
        LongRunningController controller = new LongRunningController();
        var pending = session.pendingLongRunningTransitionRequest();
        if (pending.isPresent()) {
            if (pending.get().targetStage().normalized() != LongRunningStage.RUNNING) {
                return new LaunchOutcome(
                        EvalResult.ExecutionStatus.WORKFLOW_FAILED,
                        planningMetrics,
                        "plan=COMPLETED launch=NOT_STARTED",
                        "planning requested unsupported transition to "
                                + pending.get().targetStage());
            }
            controller.applyPendingRequest(session, "eval-harness", null);
        } else {
            controller.requestAndApply(
                    session,
                    LongRunningStage.RUNNING,
                    LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                    evalCase.instruction(),
                    null,
                    "eval-harness",
                    null);
        }

        // Drive the real bounded worker-cycle loop to execution.
        LongRunningLauncher launcher = new LongRunningLauncher(
                context.runtime().newWorkerRunner(
                        context.budget().maxWorkerIterations(),
                        workerSession -> {
                            if (context.traceCollector() != null) {
                                context.traceCollector().recordSession(
                                        workerSession, ToolInvocation.Phase.WORKER);
                            }
                        }));
        LongRunningLauncher.LaunchResult result = launcher.run(
                task.taskId(), dir, session, context.budget().maxWorkerCycles());

        String summary = "plan=" + planning.finishReason().name() + " launch=" + result.status().name();
        RunMetrics metrics = planningMetrics.plus(new RunMetrics(
                0,
                result.workerIterations(),
                result.workersLaunched(),
                result.toolCalls(),
                result.tokenUsage()));
        return new LaunchOutcome(
                executionStatus(result.status()),
                metrics,
                summary,
                result.message(),
                result.message(),
                result.quiescent());
    }

    private static EvalResult.ExecutionStatus executionStatus(FinishReason reason) {
        return switch (reason) {
            case COMPLETED -> EvalResult.ExecutionStatus.COMPLETED;
            case MAX_ITERATIONS -> EvalResult.ExecutionStatus.MAX_ITERATIONS;
            case API_ERROR -> EvalResult.ExecutionStatus.API_ERROR;
            case MODEL_TRUNCATED -> EvalResult.ExecutionStatus.MODEL_TRUNCATED;
            case PERMISSION_CANCELLED -> EvalResult.ExecutionStatus.PERMISSION_DENIED;
            case CANCELLED -> EvalResult.ExecutionStatus.CANCELLED;
        };
    }

    private static EvalResult.ExecutionStatus executionStatus(LongRunningLauncher.LaunchStatus status) {
        return switch (status) {
            case COMPLETED -> EvalResult.ExecutionStatus.COMPLETED;
            case INTERRUPTED -> EvalResult.ExecutionStatus.CANCELLED;
            case MAX_WORKERS_EXHAUSTED -> EvalResult.ExecutionStatus.MAX_ITERATIONS;
            case ALREADY_RUNNING, BLOCKED, FAILED, NEEDS_USER ->
                    EvalResult.ExecutionStatus.WORKFLOW_FAILED;
        };
    }
}
