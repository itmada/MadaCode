package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.core.model.TokenUsage;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Append-only jsonl event store for Turn events.
 *
 * <p>Persists each event as a single JSON line under
 * {@code <baseDir>/<sessionId>.turns/<turnId>.jsonl}.
 * Events are readable back via {@link #read} and {@link #findTerminal}.
 */
public final class TurnLog {

    private final Path baseDir;
    private final ObjectMapper mapper;

    public TurnLog(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
        this.mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new InstantSerializer());
        module.addDeserializer(Instant.class, new InstantDeserializer());
        this.mapper.registerModule(module);
    }

    public void append(String sessionId, TurnEvent event) {
        Path file = turnFile(sessionId, event.turnId());
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory for turn log", e);
        }
        try (FileWriter w = new FileWriter(file.toFile(), true)) {
            w.write(mapper.writeValueAsString(event));
            w.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write turn event: " + event.turnId(), e);
        }
    }

    /** Reads all events for the given turn in order. Returns empty list if the file does not exist. */
    public List<TurnEvent> read(String sessionId, String turnId) {
        Path file = turnFile(sessionId, turnId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<TurnEvent> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                try {
                    events.add(mapper.readValue(line, TurnEvent.class));
                } catch (JsonProcessingException ignored) {
                    // Skip lines that can't be parsed (e.g., written by an older format)
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read turn log: " + turnId, e);
        }
        return List.copyOf(events);
    }

    /** Returns the terminal Finished event for this turn, or empty if the turn has not ended. */
    public Optional<TurnEvent.Finished> findTerminal(String sessionId, String turnId) {
        return read(sessionId, turnId).stream()
                .filter(e -> e instanceof TurnEvent.Finished)
                .map(e -> (TurnEvent.Finished) e)
                .findFirst();
    }

    /** Scans all {@code <sid>.turns/} directories for turns without a Finished event. */
    public List<String> findUnfinished() {
        List<String> unfinished = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return unfinished;
        }
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(baseDir, "*.turns")) {
            for (Path turnsDir : sessions) {
                if (!Files.isDirectory(turnsDir)) continue;
                String dirName = turnsDir.getFileName().toString();
                String sessionId = dirName.substring(0, dirName.length() - ".turns".length());
                try (DirectoryStream<Path> turnFiles = Files.newDirectoryStream(turnsDir, "turn_*.jsonl")) {
                    for (Path turnFile : turnFiles) {
                        String fileName = turnFile.getFileName().toString();
                        String turnId = fileName.substring(0, fileName.length() - ".jsonl".length());
                        if (findTerminal(sessionId, turnId).isEmpty()) {
                            unfinished.add(turnId);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan turn directories", e);
        }
        return unfinished;
    }

    /** Appends a Finished(FAILED, "process restarted") event, marking the turn unclean. */
    public void markRestartFailed(String turnId) {
        String sessionId = findSessionForTurn(turnId);
        TurnEvent.Finished event = new TurnEvent.Finished(
                turnId, Instant.now(), TerminalState.failed("process restarted"));
        append(sessionId, event);
    }

    public Path turnFile(String sessionId, String turnId) {
        return baseDir.resolve(sessionId + ".turns").resolve(turnId + ".jsonl");
    }

    // ---- helpers ------------------------------------------------------------

    private String findSessionForTurn(String turnId) {
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(baseDir, "*.turns")) {
            for (Path turnsDir : sessions) {
                if (!Files.isDirectory(turnsDir)) continue;
                if (Files.exists(turnsDir.resolve(turnId + ".jsonl"))) {
                    String dirName = turnsDir.getFileName().toString();
                    return dirName.substring(0, dirName.length() - ".turns".length());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to find session for turn " + turnId, e);
        }
        throw new IllegalStateException("Cannot find session for turn " + turnId);
    }

    // ---- Jackson helpers ---------------------------------------------------

    private static class InstantSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.toString());
        }
    }

    private static class InstantDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return Instant.parse(p.getText());
        }
    }
}
