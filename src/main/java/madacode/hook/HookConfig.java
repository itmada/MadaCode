package madacode.hook;

import java.util.List;

public record HookConfig(List<HookEntry> hooks) {

    public record HookEntry(
            HookEvent event,
            String command,
            long timeoutMs,
            boolean blockOnFailure) {
    }

    public static HookConfig empty() {
        return new HookConfig(List.of());
    }

    public List<HookEntry> forEvent(HookEvent event) {
        return hooks.stream()
                .filter(h -> h.event() == event)
                .toList();
    }
}
