package madacode.longrunning;

import madacode.core.session.LongRunningStage;

import java.util.Objects;

/**
 * Pure state-machine decision for a lifecycle event. Side effects such as
 * writing task.json, appending events, or starting runtime happen after this
 * decision is produced.
 */
public record LongRunningLifecycleDecision(
        LongRunningStage source,
        LongRunningLifecycleEvent event,
        LongRunningStage target,
        LongRunningTransitions.TerminalOutcome terminalOutcome,
        LongRunningTransitions.InterruptCause interruptCause) {

    public LongRunningLifecycleDecision {
        source = Objects.requireNonNull(source, "source");
        event = Objects.requireNonNull(event, "event");
        target = Objects.requireNonNull(target, "target");
    }

    public LongRunningTransitions.Trigger trigger() {
        return event.trigger();
    }

    public String reason() {
        return event.reason();
    }

    public boolean stateChanged() {
        return source != target;
    }

    public boolean isTerminal() {
        return target.isTerminal();
    }

    public boolean isInterrupt() {
        return target == LongRunningStage.INTERRUPT;
    }
}
