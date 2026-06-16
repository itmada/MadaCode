package madacode.longrunning;

import madacode.core.session.LongRunningStage;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The long-running lifecycle state machine as a single, typed, explicit table.
 *
 * <p>Replaces the scattered string-literal {@code reason} matrix: every legal
 * transition is one {@link Edge} of {@code (from, trigger, to)}, every persisted
 * lifecycle reason is a typed {@link Trigger}, terminal triggers carry their
 * {@link TerminalOutcome}, and interrupt triggers carry a typed
 * {@link InterruptCause}. Validation, dispatch, lifecycle persistence, and the
 * request-tool schema all derive from this one source so they cannot drift.
 */
public final class LongRunningTransitions {

    private LongRunningTransitions() {}

    /** A typed transition trigger. {@link #wire()} is the persisted reason string. */
    public enum Trigger {
        // User / control-initiated (requestable through the transition tool)
        USER_CONFIRMED_START("user_confirmed_start"),
        USER_REQUESTED_CANCEL("user_requested_cancel"),
        RESUME_AFTER_INTERRUPT("resume_after_interrupt"),
        TASK_COMPLETED("task_completed"),
        FAILURE("failure"),
        // Mechanical interrupts produced by the launcher / coordinator / runtime
        USER_INTERRUPTED("user_interrupted"),
        NEEDS_USER("needs_user"),
        WORKER_BLOCKED("worker_blocked"),
        WORKER_FAILED("worker_failed"),
        WORKER_CRASH("worker_crash"),
        WORKER_CYCLE_BUDGET_EXHAUSTED("worker_cycle_budget_exhausted"),
        WORKER_ITERATION_BUDGET_EXHAUSTED("worker_iteration_budget_exhausted"),
        WORKER_MODEL_TRUNCATED("worker_model_truncated"),
        WORKER_API_ERROR("worker_api_error"),
        WORKER_CANCELLED("worker_cancelled"),
        NO_REPORT("no_report"),
        COMPLETION_FAILED("completion_failed"),
        EXECUTION_START_FAILED("execution_start_failed"),
        PROCESS_RESTARTED("process_restarted"),
        RUNTIME_START_FAILED("runtime_start_failed"),
        RUNTIME_FAILED("runtime_failed"),
        RUNTIME_CLOSED("runtime_closed"),
        RECOVERY_FAILED("recovery_failed"),
        ALREADY_RUNNING_ELSEWHERE("already_running_elsewhere");

        private final String wire;

        Trigger(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Optional<Trigger> fromWire(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            String normalized = value.strip();
            return Arrays.stream(values()).filter(t -> t.wire.equals(normalized)).findFirst();
        }
    }

    /** What a terminal trigger means for task lifecycle persistence. */
    public enum TerminalOutcome { COMPLETED, CANCELLED, FAILED }

    /** Typed reason a task entered INTERRUPT, derived from the trigger. */
    public enum InterruptCause {
        USER, NEEDS_USER, BLOCKED, FAILED, CRASH, BUDGET, NO_REPORT,
        COMPLETION_FAILED, EXECUTION_START_FAILED, PROCESS_RESTARTED, RUNTIME, RECOVERY,
        ALREADY_RUNNING_ELSEWHERE, OTHER
    }

    private record Edge(LongRunningStage from, Trigger trigger, LongRunningStage to) {}

    private static final Set<Edge> TABLE = Set.of(
            // DRAFT
            new Edge(LongRunningStage.DRAFT, Trigger.USER_CONFIRMED_START, LongRunningStage.RUNNING),
            new Edge(LongRunningStage.DRAFT, Trigger.USER_REQUESTED_CANCEL, LongRunningStage.CANCELLED),
            // RUNNING -> INTERRUPT (one edge per mechanical cause)
            new Edge(LongRunningStage.RUNNING, Trigger.USER_INTERRUPTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.NEEDS_USER, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_BLOCKED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_CRASH, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_CYCLE_BUDGET_EXHAUSTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_ITERATION_BUDGET_EXHAUSTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_MODEL_TRUNCATED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_API_ERROR, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.WORKER_CANCELLED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.NO_REPORT, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.COMPLETION_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.EXECUTION_START_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.PROCESS_RESTARTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.RUNTIME_START_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.RUNTIME_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.RUNTIME_CLOSED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.RECOVERY_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.RUNNING, Trigger.ALREADY_RUNNING_ELSEWHERE, LongRunningStage.INTERRUPT),
            // RUNNING -> terminal
            new Edge(LongRunningStage.RUNNING, Trigger.USER_REQUESTED_CANCEL, LongRunningStage.CANCELLED),
            new Edge(LongRunningStage.RUNNING, Trigger.TASK_COMPLETED, LongRunningStage.COMPLETED),
            new Edge(LongRunningStage.RUNNING, Trigger.FAILURE, LongRunningStage.FAILED),
            // INTERRUPT
            new Edge(LongRunningStage.INTERRUPT, Trigger.RESUME_AFTER_INTERRUPT, LongRunningStage.RUNNING),
            new Edge(LongRunningStage.INTERRUPT, Trigger.USER_REQUESTED_CANCEL, LongRunningStage.CANCELLED),
            new Edge(LongRunningStage.INTERRUPT, Trigger.FAILURE, LongRunningStage.FAILED),
            new Edge(LongRunningStage.INTERRUPT, Trigger.USER_INTERRUPTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.NEEDS_USER, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_BLOCKED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_CRASH, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_CYCLE_BUDGET_EXHAUSTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_ITERATION_BUDGET_EXHAUSTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_MODEL_TRUNCATED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_API_ERROR, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.WORKER_CANCELLED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.NO_REPORT, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.COMPLETION_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.EXECUTION_START_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.PROCESS_RESTARTED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.RUNTIME_START_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.RUNTIME_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.RUNTIME_CLOSED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.RECOVERY_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.INTERRUPT, Trigger.ALREADY_RUNNING_ELSEWHERE, LongRunningStage.INTERRUPT),
            // DRAFT mechanical failures are possible before a launcher starts cleanly.
            new Edge(LongRunningStage.DRAFT, Trigger.EXECUTION_START_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.DRAFT, Trigger.RUNTIME_START_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.DRAFT, Trigger.RECOVERY_FAILED, LongRunningStage.INTERRUPT),
            new Edge(LongRunningStage.DRAFT, Trigger.FAILURE, LongRunningStage.FAILED));

    /** Triggers the control session may request via the transition tool. */
    private static final List<Trigger> REQUESTABLE = List.of(
            Trigger.USER_CONFIRMED_START,
            Trigger.USER_REQUESTED_CANCEL,
            Trigger.RESUME_AFTER_INTERRUPT,
            Trigger.FAILURE);

    public static boolean isAllowed(LongRunningStage from, Trigger trigger, LongRunningStage to) {
        return TABLE.contains(new Edge(from, trigger, to));
    }

    public static LongRunningStage targetFor(LongRunningStage from, Trigger trigger) {
        return transition(from, trigger)
                .orElseThrow(() -> new IllegalStateException(
                        "Transition not allowed: " + from + " --" + trigger.wire()
                                + "--> ?. Legal from " + from + ": " + legalTargetsFrom(from)))
                .to();
    }

    public static Optional<Transition> transition(LongRunningStage from, Trigger trigger) {
        if (from == null || trigger == null) {
            return Optional.empty();
        }
        List<Edge> matches = TABLE.stream()
                .filter(e -> e.from() == from && e.trigger() == trigger)
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous long-running transition for " + from + " --" + trigger.wire() + "--> ?");
        }
        return matches.stream().findFirst().map(e -> new Transition(e.from(), e.trigger(), e.to()));
    }

    public static TerminalOutcome terminalOutcomeFor(Trigger trigger) {
        return switch (trigger) {
            case TASK_COMPLETED -> TerminalOutcome.COMPLETED;
            case USER_REQUESTED_CANCEL -> TerminalOutcome.CANCELLED;
            case FAILURE -> TerminalOutcome.FAILED;
            default -> null;
        };
    }

    public static InterruptCause causeFor(Trigger trigger) {
        return switch (trigger) {
            case USER_INTERRUPTED -> InterruptCause.USER;
            case NEEDS_USER -> InterruptCause.NEEDS_USER;
            case WORKER_BLOCKED -> InterruptCause.BLOCKED;
            case WORKER_FAILED, FAILURE -> InterruptCause.FAILED;
            case WORKER_CRASH -> InterruptCause.CRASH;
            case WORKER_CYCLE_BUDGET_EXHAUSTED, WORKER_ITERATION_BUDGET_EXHAUSTED -> InterruptCause.BUDGET;
            case NO_REPORT -> InterruptCause.NO_REPORT;
            case WORKER_MODEL_TRUNCATED, WORKER_API_ERROR, WORKER_CANCELLED -> InterruptCause.NO_REPORT;
            case COMPLETION_FAILED -> InterruptCause.COMPLETION_FAILED;
            case EXECUTION_START_FAILED -> InterruptCause.EXECUTION_START_FAILED;
            case PROCESS_RESTARTED -> InterruptCause.PROCESS_RESTARTED;
            case RUNTIME_START_FAILED, RUNTIME_FAILED, RUNTIME_CLOSED -> InterruptCause.RUNTIME;
            case RECOVERY_FAILED -> InterruptCause.RECOVERY;
            case ALREADY_RUNNING_ELSEWHERE -> InterruptCause.ALREADY_RUNNING_ELSEWHERE;
            default -> InterruptCause.OTHER;
        };
    }

    /** Typed interrupt cause for a persisted reason string, for UI / recovery. */
    public static InterruptCause causeForReason(String reason) {
        return Trigger.fromWire(reason).map(LongRunningTransitions::causeFor).orElse(InterruptCause.OTHER);
    }

    /** Wire reasons offered by the transition request tool schema. */
    public static String[] requestableReasonWires() {
        return REQUESTABLE.stream().map(Trigger::wire).toArray(String[]::new);
    }

    /** Human-readable label for the transition table, used in error messages. */
    public static String describe(LongRunningStage from, Trigger trigger, LongRunningStage to) {
        return from + " --" + trigger.wire() + "--> " + to;
    }

    static String legalTargetsFrom(LongRunningStage from) {
        return TABLE.stream()
                .filter(e -> e.from() == from)
                .map(e -> e.trigger().wire() + "->" + e.to())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    public record Transition(
            LongRunningStage from,
            Trigger trigger,
            LongRunningStage to) {
        public TerminalOutcome terminalOutcome() {
            return terminalOutcomeFor(trigger);
        }

        public InterruptCause interruptCause() {
            return causeFor(trigger);
        }
    }
}
