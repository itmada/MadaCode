package madacode.longrunning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class LongRunningEventLog {

    private static final int MONITOR_EVENT_TAIL_BYTES = 1_048_576;
    private static final Map<Path, Object> APPEND_LOCKS = new ConcurrentHashMap<>();

    private final LongRunningTaskRepository repository;
    private final ObjectMapper mapper;

    LongRunningEventLog(LongRunningTaskRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    void appendEvent(String taskId, LongRunningTaskEvent event) {
        String safeTaskId = repository.validateTaskId(taskId);
        if (!safeTaskId.equals(event.taskId())) {
            throw new LongRunningTaskStoreException(
                    "Event task id " + event.taskId() + " does not match active task " + safeTaskId);
        }
        Path eventsFile = repository.eventsFile(safeTaskId);
        synchronized (appendLock(eventsFile)) {
            try (FileChannel channel = FileChannel.open(
                    eventsFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 var ignored = channel.lock()) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(
                        mapper.writeValueAsString(serializeEvent(event)) + System.lineSeparator());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(false);
            } catch (IOException exception) {
                throw new LongRunningTaskStoreException("Failed to append event for task " + safeTaskId, exception);
            }
        }
    }

    List<LongRunningTaskEvent> readEvents(String taskId) {
        String safeTaskId = repository.validateTaskId(taskId);
        Path eventsFile = repository.eventsFile(safeTaskId);
        try {
            List<LongRunningTaskEvent> events = new ArrayList<>();
            List<String> lines = Files.readAllLines(eventsFile);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(deserializeEvent(mapper.readTree(line), safeTaskId));
                } catch (IOException exception) {
                    if (i == lines.size() - 1) {
                        continue;
                    }
                    throw exception;
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to read events for task " + safeTaskId, exception);
        }
    }

    List<LongRunningTaskEvent> readRecentEvents(String taskId, int limit) {
        if (limit < 1) {
            return List.of();
        }
        String safeTaskId = repository.validateTaskId(taskId);
        Path eventsFile = repository.eventsFile(safeTaskId);
        try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.READ)) {
            long size = channel.size();
            int byteCount = (int) Math.min(size, MONITOR_EVENT_TAIL_BYTES);
            long start = size - byteCount;
            ByteBuffer buffer = ByteBuffer.allocate(byteCount);
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Keep reading until the bounded tail is complete.
            }
            buffer.flip();
            String tail = StandardCharsets.UTF_8.decode(buffer).toString();
            if (start > 0) {
                int firstNewline = tail.indexOf('\n');
                tail = firstNewline < 0 ? "" : tail.substring(firstNewline + 1);
            }
            String[] lines = tail.split("\\R");
            int from = Math.max(0, lines.length - limit);
            List<LongRunningTaskEvent> events = new ArrayList<>();
            for (int i = from; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                try {
                    events.add(deserializeEvent(mapper.readTree(lines[i]), safeTaskId));
                } catch (IOException exception) {
                    if (i == lines.length - 1) {
                        continue;
                    }
                    throw exception;
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to read recent events for task " + safeTaskId, exception);
        }
    }

    void ensureEventLogFile(Path eventsFile, String taskId) {
        if (Files.isRegularFile(eventsFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createDirectories(eventsFile.getParent());
            Files.writeString(eventsFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to initialize event log for task " + taskId, exception);
        }
    }

    private ObjectNode serializeEvent(LongRunningTaskEvent event) {
        ObjectNode root = mapper.createObjectNode();
        root.put("timestamp", event.timestamp().toString());
        root.put("type", event.type());
        root.put("taskId", event.taskId());
        if (event.sessionId() != null) {
            root.put("sessionId", event.sessionId());
        }
        if (event.stage() != null) {
            root.put("stage", event.stage());
        }
        if (event.action() != null) {
            root.put("action", event.action());
        }
        if (event.success() != null) {
            root.put("success", event.success());
        }
        if (event.message() != null) {
            root.put("message", event.message());
        }
        ObjectNode details = mapper.createObjectNode();
        for (Map.Entry<String, String> entry : event.details().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                details.put(entry.getKey(), entry.getValue());
            }
        }
        root.set("details", details);
        return root;
    }

    private LongRunningTaskEvent deserializeEvent(JsonNode root, String expectedTaskId) {
        String taskId = requiredText(root, "taskId");
        if (!expectedTaskId.equals(taskId)) {
            throw new LongRunningTaskStoreException(
                    "Event task id " + taskId + " does not match expected " + expectedTaskId);
        }
        return new LongRunningTaskEvent(
                Instant.parse(requiredText(root, "timestamp")),
                requiredText(root, "type"),
                taskId,
                optionalText(root, "sessionId").orElse(null),
                optionalText(root, "stage").orElse(null),
                optionalText(root, "action").orElse(null),
                root.has("success") ? root.path("success").asBoolean() : null,
                optionalText(root, "message").orElse(null),
                objectText(root.path("details")));
    }

    private static Object appendLock(Path path) {
        return APPEND_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new Object());
    }

    private static Optional<String> optionalText(JsonNode root, String field) {
        if (!root.has(field) || root.path(field).isNull()) {
            return Optional.empty();
        }
        String text = root.path(field).asText();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static String requiredText(JsonNode root, String field) {
        return optionalText(root, field)
                .orElseThrow(() -> new LongRunningTaskStoreException("Missing required field: " + field));
    }

    private static Map<String, String> objectText(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
        return Map.copyOf(values);
    }
}
