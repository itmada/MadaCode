package madacode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void v1ToV2_addsMissingTasksAndTodos() {
        ObjectNode root = minimalV1();
        root.put("schemaVersion", 1);

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        assertEquals(SchemaMigrator.CURRENT, migrated.get("schemaVersion").asInt());
        assertTrue(migrated.get("tasks").isArray());
        assertTrue(migrated.get("todos").isArray());
    }

    @Test
    void v4ToV5_renamesDependencyIdsToBlockedBy() {
        ObjectNode root = minimalV4WithTask("PENDING", "dep1");

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        assertEquals(SchemaMigrator.CURRENT, migrated.get("schemaVersion").asInt());
        ObjectNode task = (ObjectNode) migrated.get("tasks").get(0);
        assertTrue(task.has("blockedBy"));
        assertFalse(task.has("dependencyIds"));
        assertEquals("dep1", task.get("blockedBy").get(0).asText());
    }

    @Test
    void v4ToV5_mapsFailedStatusToCompleted() {
        ObjectNode root = minimalV4WithTask("FAILED", null);

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        ObjectNode task = (ObjectNode) migrated.get("tasks").get(0);
        assertEquals("COMPLETED", task.get("status").asText());
    }

    @Test
    void v4ToV5_mapsStoppedStatusToCompleted() {
        ObjectNode root = minimalV4WithTask("STOPPED", null);

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        ObjectNode task = (ObjectNode) migrated.get("tasks").get(0);
        assertEquals("COMPLETED", task.get("status").asText());
    }

    @Test
    void v4ToV5_preservesValidStatus() {
        ObjectNode root = minimalV4WithTask("PENDING", null);

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        ObjectNode task = (ObjectNode) migrated.get("tasks").get(0);
        assertEquals("PENDING", task.get("status").asText());
    }

    @Test
    void v4ToV5_removesBlocksField() {
        ObjectNode root = minimalV4WithTask("PENDING", null);
        ObjectNode task = (ObjectNode) root.get("tasks").get(0);
        task.putArray("blocks").add("other");

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        ObjectNode migratedTask = (ObjectNode) migrated.get("tasks").get(0);
        assertFalse(migratedTask.has("blocks"));
    }

    @Test
    void migrateToLatest_alreadyCurrentIsNoop() {
        ObjectNode root = minimalV1();
        root.put("schemaVersion", SchemaMigrator.CURRENT);
        root.putArray("tasks");
        root.putArray("todos");

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        assertEquals(SchemaMigrator.CURRENT, migrated.get("schemaVersion").asInt());
    }

    @Test
    void migrateToLatest_chainsAllSteps() {
        ObjectNode root = minimalV1();
        root.put("schemaVersion", 1);

        ObjectNode migrated = SchemaMigrator.migrateToLatest(root);

        assertEquals(SchemaMigrator.CURRENT, migrated.get("schemaVersion").asInt());
        assertTrue(migrated.has("tasks"));
        assertTrue(migrated.has("todos"));
    }

    // ---- helpers ----

    private ObjectNode minimalV1() {
        ObjectNode root = mapper.createObjectNode();
        root.put("sessionId", "test-session");
        root.put("createdAt", "2026-01-01T00:00:00Z");
        root.put("workingDirectory", "/tmp");
        root.putArray("messages");
        return root;
    }

    private ObjectNode minimalV4WithTask(String status, String dependencyId) {
        ObjectNode root = minimalV1();
        root.put("schemaVersion", 4);
        root.putArray("todos");
        ArrayNode tasks = root.putArray("tasks");
        ObjectNode task = tasks.addObject();
        task.put("id", "1");
        task.put("title", "test task");
        task.put("description", "");
        task.put("status", status);
        task.put("createdAt", "2026-01-01T00:00:00Z");
        task.put("updatedAt", "2026-01-01T00:00:00Z");
        if (dependencyId != null) {
            task.putArray("dependencyIds").add(dependencyId);
        } else {
            task.putArray("dependencyIds");
        }
        return root;
    }
}
