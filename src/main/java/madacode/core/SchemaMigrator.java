package madacode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Migrates a session transcript JSON from any supported schema version
 * to the current version. Each step is an independent pure function
 * that transforms version N to version N+1.
 */
final class SchemaMigrator {

    static final int CURRENT = 5;

    private static final Map<Integer, UnaryOperator<ObjectNode>> STEPS = Map.of(
            1, SchemaMigrator::v1ToV2,
            2, SchemaMigrator::v2ToV3,
            3, SchemaMigrator::v3ToV4,
            4, SchemaMigrator::v4ToV5
    );

    private SchemaMigrator() {}

    /**
     * Applies all necessary migration steps to bring the JSON up to
     * {@link #CURRENT}. Mutates and returns the same ObjectNode.
     */
    static ObjectNode migrateToLatest(ObjectNode root) {
        int version = root.path("schemaVersion").asInt(1);
        while (version < CURRENT) {
            UnaryOperator<ObjectNode> step = STEPS.get(version);
            if (step == null) {
                throw new SessionStorageException(
                        "No migration defined for schemaVersion " + version);
            }
            root = step.apply(root);
            version++;
            root.put("schemaVersion", version);
        }
        return root;
    }

    // ---- v1 → v2: add tasks and todos arrays ----
    private static ObjectNode v1ToV2(ObjectNode root) {
        if (!root.has("tasks")) {
            root.putArray("tasks");
        }
        if (!root.has("todos")) {
            root.putArray("todos");
        }
        return root;
    }

    // ---- v2 → v3: no structural changes ----
    private static ObjectNode v2ToV3(ObjectNode root) {
        return root;
    }

    // ---- v3 → v4: no structural changes ----
    private static ObjectNode v3ToV4(ObjectNode root) {
        return root;
    }

    // ---- v4 → v5: tasks: dependencyIds → blockedBy, remove blocks,
    //               map FAILED/STOPPED status → COMPLETED ----
    private static ObjectNode v4ToV5(ObjectNode root) {
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray()) {
            return root;
        }
        for (JsonNode taskNode : tasksNode) {
            if (!(taskNode instanceof ObjectNode task)) continue;

            // rename dependencyIds → blockedBy
            if (task.has("dependencyIds")) {
                task.set("blockedBy", task.get("dependencyIds"));
                task.remove("dependencyIds");
            } else if (!task.has("blockedBy")) {
                task.putArray("blockedBy");
            }

            // remove deprecated "blocks" field
            task.remove("blocks");

            // map removed statuses to COMPLETED
            String status = task.path("status").asText("");
            if ("FAILED".equals(status) || "STOPPED".equals(status)) {
                task.put("status", "COMPLETED");
            }
        }
        return root;
    }
}
