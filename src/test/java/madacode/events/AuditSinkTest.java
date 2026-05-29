package madacode.events;

import madacode.events.sinks.AuditSink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAuditEventsAsJsonLines() throws Exception {
        Path auditPath = tempDir.resolve("audit.jsonl");
        AuditSink sink = new AuditSink(auditPath);

        sink.accept(AuditEvent.permissionDecision(
                new EventContext("session-1", "parent-1", "turn-1", "Permission"),
                "bash",
                false,
                "nope",
                "test",
                12,
                "{\"command\":\"echo hi\"}"));

        String line = Files.readString(auditPath).strip();
        JsonNode node = new ObjectMapper().readTree(line);
        assertEquals("session-1", node.path("sessionId").asText());
        assertEquals("parent-1", node.path("parentSessionId").asText());
        assertEquals("turn-1", node.path("turnId").asText());
        assertEquals("bash", node.path("tool").asText());
        assertEquals("test", node.path("permissionSource").asText());
        assertEquals("Permission", node.path("componentSource").asText());
        assertTrue(node.path("inputPreview").asText().contains("echo hi"));
    }
}
