package madacode.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "event")
@JsonSubTypes({
        @JsonSubTypes.Type(name = "started",          value = TurnEvent.Started.class),
        @JsonSubTypes.Type(name = "finished",         value = TurnEvent.Finished.class)
})
public sealed interface TurnEvent {
    String turnId();
    Instant at();

    record Started(String turnId, Instant at, String input) implements TurnEvent {}
    record Finished(String turnId, Instant at, TerminalState terminal) implements TurnEvent {}
}
