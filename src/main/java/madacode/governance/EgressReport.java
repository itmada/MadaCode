package madacode.governance;

import java.util.List;

public record EgressReport(EgressObservation observation, List<EgressEvent> events) {

    public EgressReport {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static EgressReport unavailable() {
        return new EgressReport(EgressObservation.UNAVAILABLE, List.of());
    }
}
