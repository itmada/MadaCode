package madacode.eval;

import madacode.cli.UserPromptChannel;
import madacode.core.engine.QueryEngine;
import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnResult;
import madacode.longrunning.LongRunningLauncher;
import madacode.longrunning.LongRunningTaskStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Long-running mode launcher: drives the real autonomous worker lifecycle for large tasks.
 *
 * <p>Flow, all on the production components:
 * <ol>
 *   <li>Run scripted Controller turns through {@link QueryEngine#runTurn} so the Controller
 *       can clarify, initialize the long-running environment, and apply RUNNING through the
 *       interactive state-transition tool.</li>
 *   <li>Drive bounded worker cycles via {@link LongRunningLauncher#run} until terminal
 *       or the cycle cap ({@code maxCycles}).</li>
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

        // The production lifecycle requires DRAFT planning to initialize the
        // long-running environment through longrun_environment_update and then
        // apply RUNNING through longrun_state_transition. Cases may script multiple
        // user turns (clarification + confirmation) just like common-mode eval.
        QueryEngine engine = context.runtime().newEngine(context.budget().maxIterations());
        List<ConversationTurn> conversation = evalCase.effectiveConversation();
        int planningIterations = 0;
        TurnResult planning = null;
        String finalText = "";
        try {
            for (ConversationTurn scriptedTurn : conversation) {
                if (session.longRunningStage() == LongRunningStage.RUNNING) {
                    break;
                }
                if (scriptedTurn.trigger() == ConversationTurn.Trigger.WHEN_AGENT_ASKS
                        && !looksLikeQuestion(finalText)) {
                    break;
                }
                planning = context.runtime().runTurn(
                        engine,
                        session,
                        scriptedTurn.text(),
                        context.remainingTime(),
                        AutoApprovePromptChannel.INSTANCE);
                planningIterations += planning.iterations();
                if (context.traceCollector() != null) {
                    context.traceCollector().recordSession(session, ToolInvocation.Phase.CONTROL);
                }
                finalText = planning.finalText();
                if (planning.finishReason() != FinishReason.COMPLETED) {
                    return new LaunchOutcome(
                            executionStatus(planning.finishReason()),
                            RunMetrics.fromSession(session, planningIterations),
                            "plan=" + terminalSummary(planning),
                            detail(planning),
                            planning.finalText(),
                            true,
                            planning.apiFailure());
                }
            }
        } catch (madacode.bootstrap.HeadlessAgentRuntime.HeadlessTurnTimeoutException e) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.TIMED_OUT,
                    RunMetrics.fromSession(session, planningIterations),
                    "plan=TIMED_OUT",
                    e.getMessage(),
                    finalText,
                    e.quiescent());
        }
        if (planning == null) {
            throw new IllegalStateException("conversation did not execute a user turn");
        }
        RunMetrics planningMetrics = RunMetrics.fromSession(session, planningIterations);

        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.WORKFLOW_FAILED,
                    planningMetrics,
                    "plan=COMPLETED launch=NOT_STARTED",
                    "planning completed without initializing the long-running environment");
        }
        if (store.readFeatureList(taskId).isEmpty()) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.WORKFLOW_FAILED,
                    planningMetrics,
                    "plan=COMPLETED launch=NOT_STARTED",
                    "planning completed with an empty long-running environment feature list");
        }

        if (session.longRunningStage() != LongRunningStage.RUNNING) {
            return new LaunchOutcome(
                    EvalResult.ExecutionStatus.WORKFLOW_FAILED,
                    planningMetrics,
                    "plan=COMPLETED launch=NOT_STARTED",
                    "planning completed without applying RUNNING");
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
                        },
                        subAgentSession -> {
                            if (context.traceCollector() != null) {
                                context.traceCollector().trackSubAgent(subAgentSession);
                            }
                        }));
        LongRunningLauncher.LaunchResult result = launcher.run(
                taskId, dir, session, context.budget().maxWorkerCycles());

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

    private static boolean looksLikeQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        return normalized.contains("?")
                || normalized.contains("？")
                || normalized.matches("(?s).*(请问|能否|可以提供|需要.*吗|which|what|could you|can you).*");
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

    private static String terminalSummary(TurnResult turn) {
        return turn.apiFailure() == null
                ? turn.finishReason().name()
                : turn.finishReason().name() + " " + turn.apiFailure().detail();
    }

    private static String detail(TurnResult turn) {
        return turn.apiFailure() == null
                ? turn.finalText()
                : turn.finalText() + "\n" + turn.apiFailure().detail();
    }

    /**
     * Headless stand-in for interactive prompts during long-running eval planning.
     *
     * <p>{@code ask_user_question} calls {@link #askQuestion(QuestionForm)}, not the
     * older chooseOne/chooseMany helpers. The default {@code askQuestion} returns empty,
     * which the tool records as {@code (no answer)} and steers the Controller into
     * guessing — so this channel must implement the unified API.
     */
    private enum AutoApprovePromptChannel implements UserPromptChannel {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<List<String>> askQuestion(QuestionForm form) {
            if (form == null) {
                return Optional.of(List.of());
            }
            List<ChannelOption> options = form.options();
            if (options.isEmpty()) {
                // Free-text clarification: affirm so planning can proceed to initialize.
                return Optional.of(List.of("yes, proceed with the recommended defaults"));
            }
            if (form.multiSelect()) {
                return Optional.of(options.stream().map(ChannelOption::label).toList());
            }
            // Tool docs put the recommended option first.
            return Optional.of(List.of(options.getFirst().label()));
        }

        @Override
        public Optional<String> chooseOne(String title, List<ChannelOption> options) {
            return options.isEmpty() ? Optional.empty() : Optional.of(options.getFirst().label());
        }

        @Override
        public Optional<List<String>> chooseMany(String title, List<ChannelOption> options) {
            return Optional.of(options.stream().map(ChannelOption::label).toList());
        }

        @Override
        public Optional<String> freeText(String prompt) {
            return Optional.of("yes, proceed with the recommended defaults");
        }

        @Override
        public boolean confirm(String prompt) {
            return true;
        }
    }
}
