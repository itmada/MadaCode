package madacode.core.turn;

import madacode.core.model.*;
import madacode.core.session.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

class TurnEventSerializationTest {

    private ObjectMapper mapper;
    private static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override public Class<Instant> handledType() { return Instant.class; }
            @Override public void serialize(Instant v, com.fasterxml.jackson.core.JsonGenerator g,
                    com.fasterxml.jackson.databind.SerializerProvider p) throws java.io.IOException {
                g.writeString(v.toString());
            }
        });
        module.addDeserializer(Instant.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override public Instant deserialize(com.fasterxml.jackson.core.JsonParser p,
                    com.fasterxml.jackson.databind.DeserializationContext c) throws java.io.IOException {
                return Instant.parse(p.getText());
            }
        });
        mapper.registerModule(module);
    }

    static Stream<TurnEvent> allEventTypes() {
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        return Stream.of(
                new TurnEvent.Started("t1", now, "hello"),
                new TurnEvent.Finished("t1", now, TerminalState.done()),
                new TurnEvent.Finished("t1", now, TerminalState.canceled("esc")),
                new TurnEvent.Finished("t1", now, TerminalState.failed("boom"))
        );
    }

    @ParameterizedTest
    @MethodSource("allEventTypes")
    void serializationRoundTrip(TurnEvent original) throws Exception {
        String json = mapper.writeValueAsString(original);
        TurnEvent recovered = mapper.readValue(json, TurnEvent.class);
        assertEquals(original, recovered,
                "Roundtrip failed for: " + original.getClass().getSimpleName());
    }

    @Test
    void startedEventJsonContainsTypeDiscriminator() throws Exception {
        TurnEvent.Started s = new TurnEvent.Started("t1", NOW, "hello");
        String json = mapper.writeValueAsString(s);
        assertTrue(json.contains("\"event\":\"started\""), "JSON must contain event discriminator: " + json);
        assertTrue(json.contains("\"hello\""), "JSON must contain input: " + json);
    }

    @Test
    void finishedEventJsonContainsTerminalState() throws Exception {
        TurnEvent.Finished f = new TurnEvent.Finished("t1", NOW, TerminalState.canceled("esc"));
        String json = mapper.writeValueAsString(f);
        assertTrue(json.contains("\"event\":\"finished\""), "JSON must contain event discriminator: " + json);
        assertTrue(json.contains("CANCELED"), "JSON must contain CANCELED status: " + json);
        assertTrue(json.contains("\"esc\""), "JSON must contain cancel reason: " + json);
    }

    @Test
    void deserializeFinishedEventHasCorrectTerminalState() throws Exception {
        TurnEvent.Finished original = new TurnEvent.Finished("t1", NOW,
                TerminalState.canceled("user"));
        String json = mapper.writeValueAsString(original);
        TurnEvent recovered = mapper.readValue(json, TurnEvent.class);

        assertInstanceOf(TurnEvent.Finished.class, recovered);
        TurnEvent.Finished fin = (TurnEvent.Finished) recovered;
        assertEquals(TurnStatus.CANCELED, fin.terminal().status());
        assertEquals(TerminationCause.CANCELED, fin.terminal().cause());
        assertEquals("user", fin.terminal().reason());
    }

    @Test
    void turnCreatedWithDefaults() {
        Turn turn = Turn.create("s1", "user input");
        assertEquals("s1", turn.sessionId());
        assertEquals("user input", turn.userInput());
        assertEquals(TurnStatus.PENDING, turn.status());
        assertTrue(turn.id().startsWith("turn_"));
    }

    @Test
    void turnStatusTransitions() {
        Turn turn = Turn.create("s1", "test");
        Turn running = turn.withStatus(TurnStatus.RUNNING).withStarted(Instant.now());
        assertEquals(TurnStatus.RUNNING, running.status());

        Turn done = running.withFinished(Instant.now(), TurnStatus.DONE);
        assertEquals(TurnStatus.DONE, done.status());
        assertTrue(done.status().isTerminal());
    }
}
