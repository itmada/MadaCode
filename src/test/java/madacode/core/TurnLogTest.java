package madacode.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

class TurnLogTest {

    @TempDir
    Path tempDir;

    private TurnLog log;
    private String sessionId = "test-session-1";

    @BeforeEach
    void setUp() {
        log = new TurnLog(tempDir);
    }

    @Test
    void appendAndReadBackJsonl() throws Exception {
        TurnEvent.Started started = new TurnEvent.Started(
                "turn_abc", Instant.parse("2026-05-18T10:00:00Z"), "hello world");

        log.append(sessionId, started);

        Path file = log.turnFile(sessionId, "turn_abc");
        assertTrue(Files.exists(file), "Expected file to exist: " + file);
        String content = Files.readString(file);
        assertTrue(content.contains("turn_abc"));
        assertTrue(content.contains("hello world"));
    }

    @Test
    void findUnfinishedReturnsTurnsWithoutFinished() throws Exception {
        String turnA = "turn_aaa";
        String turnB = "turn_bbb";

        log.append(sessionId, new TurnEvent.Started(turnA, Instant.now(), "first"));
        log.append(sessionId, new TurnEvent.Finished(turnA, Instant.now(), TerminalState.done()));

        log.append(sessionId, new TurnEvent.Started(turnB, Instant.now(), "second"));
        // no Finished event for turnB

        List<String> unfinished = log.findUnfinished();
        assertEquals(1, unfinished.size());
        assertTrue(unfinished.contains(turnB));
    }

    @Test
    void allFinishedReturnsEmpty() throws Exception {
        String turn = "turn_xyz";
        log.append(sessionId, new TurnEvent.Started(turn, Instant.now(), "hi"));
        log.append(sessionId, new TurnEvent.Finished(turn, Instant.now(), TerminalState.done()));

        assertTrue(log.findUnfinished().isEmpty());
    }

    @Test
    void markRestartFailedAppendsFinishedEvent() throws Exception {
        String turn = "turn_orphan";
        log.append(sessionId, new TurnEvent.Started(turn, Instant.now(), "orphan"));

        log.markRestartFailed(turn);

        List<String> unfinished = log.findUnfinished();
        assertTrue(unfinished.isEmpty(), "turn should no longer be unfinished after markRestartFailed");

        String content = Files.readString(log.turnFile(sessionId, turn));
        assertTrue(content.contains("\"FAILED\""));
        assertTrue(content.contains("process restarted"));
    }

    @Test
    void emptyBaseDirReturnsEmptyUnfinished() {
        assertTrue(log.findUnfinished().isEmpty());
    }

    @Test
    void readReturnsEventsInOrder() {
        log.append(sessionId, new TurnEvent.Started("turn_r1", Instant.now(), "hi"));
        log.append(sessionId, new TurnEvent.Finished("turn_r1", Instant.now(), TerminalState.done()));

        List<TurnEvent> events = log.read(sessionId, "turn_r1");
        assertEquals(2, events.size());
        assertInstanceOf(TurnEvent.Started.class, events.get(0));
        assertInstanceOf(TurnEvent.Finished.class, events.get(1));
    }

    @Test
    void findTerminalReturnsFinishedEvent() {
        log.append(sessionId, new TurnEvent.Started("turn_ft", Instant.now(), "ping"));
        log.append(sessionId, new TurnEvent.Finished("turn_ft", Instant.now(),
                TerminalState.canceled("esc")));

        var terminal = log.findTerminal(sessionId, "turn_ft");
        assertTrue(terminal.isPresent());
        assertEquals(TurnStatus.CANCELED, terminal.get().terminal().status());
        assertEquals("esc", terminal.get().terminal().reason());
    }

    @Test
    void findTerminalEmptyForUnfinishedTurn() {
        log.append(sessionId, new TurnEvent.Started("turn_nf", Instant.now(), "running"));

        var terminal = log.findTerminal(sessionId, "turn_nf");
        assertTrue(terminal.isEmpty());
    }

    @Test
    void readReturnsEmptyForNonExistentTurn() {
        List<TurnEvent> events = log.read(sessionId, "turn_missing");
        assertTrue(events.isEmpty());
    }
}
