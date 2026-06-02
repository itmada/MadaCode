package madacode.cli.mode;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningTaskEvent;
import madacode.longrunning.LongRunningTaskStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates bounded automatic continuation for long-running execution.
 *
 * <p>This keeps long-running loop policy out of the REPL. The caller still owns
 * rendering, turn supervision, and persistence through the supplied
 * {@link TurnCycle}.
 */
public final class LongRunningAutoContinueRunner {

    private static final String AUTO_CONTINUE_INPUT =
            "[auto-continue] Continue the assigned long-running task.";

    private final ModeRouter modeRouter;
    private final TaskStoreFactory taskStoreFactory;

    public LongRunningAutoContinueRunner(ModeRouter modeRouter) {
        this(modeRouter, LongRunningTaskStore::new);
    }

    LongRunningAutoContinueRunner(ModeRouter modeRouter, TaskStoreFactory taskStoreFactory) {
        this.modeRouter = Objects.requireNonNull(modeRouter, "modeRouter");
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
    }

    public Result run(ConversationSession session, int maxTurns, TurnCycle turnCycle) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(turnCycle, "turnCycle");
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return new Result(Status.NOT_LONG_RUNNING, 0, "Not in long-running mode.");
        }
        if (isPreExecution(session.longRunningStage())) {
            return new Result(Status.NOT_EXECUTING, 0, "Long-running task is not executing yet.");
        }

        int completedTurns = 0;
        for (int i = 0; i < maxTurns; i++) {
            if (isTerminal(session.longRunningStage())) {
                break;
            }
            turnCycle.run(modeRouter.handle(AUTO_CONTINUE_INPUT, session));
            completedTurns++;
            if (isTerminal(session.longRunningStage()) || latestAssignmentVerificationFailed(session)) {
                break;
            }
        }
        return new Result(
                Status.COMPLETED,
                completedTurns,
                "Auto-continue completed " + completedTurns + " turn(s).");
    }

    private static boolean isPreExecution(LongRunningStage stage) {
        return stage == LongRunningStage.PLANNING
                || stage == LongRunningStage.WAITING_FOR_APPROVAL
                || stage == LongRunningStage.WAITING_FOR_TASK;
    }

    private static boolean isTerminal(LongRunningStage stage) {
        return stage == LongRunningStage.COMPLETED
                || stage == LongRunningStage.CANCELLED;
    }

    private boolean latestAssignmentVerificationFailed(ConversationSession session) {
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            List<LongRunningTaskEvent> events = taskStoreFactory.create(session.workingDirectory())
                    .readEvents(taskId);
            for (int i = events.size() - 1; i >= 0; i--) {
                LongRunningTaskEvent event = events.get(i);
                if ("assignment_verified".equals(event.type())) {
                    return Boolean.FALSE.equals(event.success());
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    public enum Status {
        NOT_LONG_RUNNING,
        NOT_EXECUTING,
        COMPLETED
    }

    public record Result(Status status, int completedTurns, String message) {}

    @FunctionalInterface
    public interface TurnCycle {
        void run(ModeExecution execution);
    }

    @FunctionalInterface
    interface TaskStoreFactory {
        LongRunningTaskStore create(Path projectDirectory);
    }
}
