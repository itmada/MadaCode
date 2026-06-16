package madacode.longrunning;

import madacode.core.session.LongRunningStage;

import java.util.Objects;

/**
 * Pure lifecycle state machine for long-running tasks.
 */
public final class LongRunningLifecycleStateMachine {

    private LongRunningLifecycleStateMachine() {}

    public static LongRunningLifecycleDecision decide(
            LongRunningStage source,
            LongRunningLifecycleEvent event) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(event, "event");
        LongRunningTransitions.Transition transition = LongRunningTransitions.transition(source, event.trigger())
                .orElseThrow(() -> new IllegalStateException(
                        "Transition not allowed: " + source + " --" + event.reason()
                                + "--> ?. Legal from " + source + ": "
                                + LongRunningTransitions.legalTargetsFrom(source)));
        return new LongRunningLifecycleDecision(
                transition.from(),
                event,
                transition.to(),
                transition.terminalOutcome(),
                transition.interruptCause());
    }
}
