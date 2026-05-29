package madacode.events.sinks;

import madacode.events.AuditEvent;
import madacode.events.EventFallback;
import madacode.events.Sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public class AuditSink implements Sink<AuditEvent> {

    private final Path auditPath;
    private final ObjectMapper mapper;

    public AuditSink(Path auditPath) {
        this(auditPath, new ObjectMapper());
    }

    public AuditSink(Path auditPath, ObjectMapper mapper) {
        this.auditPath = Objects.requireNonNull(auditPath, "auditPath").toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".mada", "permissions", "audit.jsonl");
    }

    public Path auditPath() {
        return auditPath;
    }

    @Override
    public void accept(AuditEvent event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("timestamp", event.timestamp().toString());
        node.put("sequence", event.sequence());
        node.put("sessionId", nullToEmpty(event.context().sessionId()));
        node.put("parentSessionId", nullToEmpty(event.context().parentSessionId()));
        node.put("turnId", nullToEmpty(event.context().turnId()));
        node.put("componentSource", event.context().source());
        node.put("tool", event.tool());
        node.put("allowed", event.allowed());
        node.put("reason", event.reason());
        node.put("permissionSource", event.permissionSource());
        node.put("waitMs", event.waitMs());
        node.put("inputPreview", event.inputPreview());

        try {
            Files.createDirectories(auditPath.getParent());
            Files.writeString(auditPath, mapper.writeValueAsString(node) + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            EventFallback.writeFailure("AUDIT write failed: " + auditPath, e, System.err);
        }
    }

    @Override
    public void flush(Duration timeout) {
        // Files.writeString is synchronous; nothing buffered here.
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
