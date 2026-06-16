package madacode.longrunning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Typed lifecycle input submitted to the harness-owned long-running state
 * machine. Models do not create these directly; controller, launcher, runtime,
 * recovery, or store compatibility paths translate model requests and worker
 * reports into events.
 */
public record LongRunningLifecycleEvent(
        LongRunningTransitions.Trigger trigger,
        Actor actor,
        String summary,
        Map<String, String> details) {

    public LongRunningLifecycleEvent {
        trigger = Objects.requireNonNull(trigger, "trigger");
        actor = Objects.requireNonNull(actor, "actor");
        summary = normalize(summary);
        details = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNullElse(details, Map.of())));
    }

    public static LongRunningLifecycleEvent of(LongRunningTransitions.Trigger trigger, Actor actor) {
        return new LongRunningLifecycleEvent(trigger, actor, null, Map.of());
    }

    public static LongRunningLifecycleEvent controller(LongRunningTransitions.Trigger trigger) {
        return of(trigger, Actor.CONTROLLER);
    }

    public static LongRunningLifecycleEvent launcher(LongRunningTransitions.Trigger trigger) {
        return of(trigger, Actor.LAUNCHER);
    }

    public static LongRunningLifecycleEvent runtime(LongRunningTransitions.Trigger trigger) {
        return of(trigger, Actor.RUNTIME);
    }

    public static LongRunningLifecycleEvent recovery(LongRunningTransitions.Trigger trigger) {
        return of(trigger, Actor.RECOVERY);
    }

    public static LongRunningLifecycleEvent storeCompatibility(LongRunningTransitions.Trigger trigger) {
        return of(trigger, Actor.STORE_COMPATIBILITY);
    }

    public String reason() {
        return trigger.wire();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

    public enum Actor {
        CONTROLLER,
        LAUNCHER,
        RUNTIME,
        RECOVERY,
        STORE_COMPATIBILITY
    }
}
