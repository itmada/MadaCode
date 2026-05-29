package madacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Bug 3 regression: JSON inputs that differ only in key order must produce
 * the same canonical string — otherwise the permission gate re-prompts the
 * user for semantically identical tool calls.
 */
class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void differentKeyOrderProducesSameCanonical() {
        ObjectNode a = mapper.createObjectNode();
        a.put("command", "echo hi");
        a.put("description", "test");
        a.put("timeoutSeconds", 30);

        ObjectNode b = mapper.createObjectNode();
        b.put("timeoutSeconds", 30);
        b.put("command", "echo hi");
        b.put("description", "test");

        // Jackson toString differs because insertion order differs.
        assertNotEquals(a.toString(), b.toString());
        // Canonical form is identical.
        assertEquals(CanonicalJson.canonicalize(a), CanonicalJson.canonicalize(b));
    }

    @Test
    void nestedObjectsAreSortedRecursively() {
        ObjectNode inner1 = mapper.createObjectNode();
        inner1.put("z", 1);
        inner1.put("a", 2);
        ObjectNode outer1 = mapper.createObjectNode();
        outer1.set("nested", inner1);
        outer1.put("top", "val");

        ObjectNode inner2 = mapper.createObjectNode();
        inner2.put("a", 2);
        inner2.put("z", 1);
        ObjectNode outer2 = mapper.createObjectNode();
        outer2.put("top", "val");
        outer2.set("nested", inner2);

        assertEquals(CanonicalJson.canonicalize(outer1), CanonicalJson.canonicalize(outer2));
    }

    @Test
    void specialCharactersAreEscaped() {
        ObjectNode node = mapper.createObjectNode();
        node.put("cmd", "echo \"hello\nworld\"");

        String canonical = CanonicalJson.canonicalize(node);
        assertEquals("{\"cmd\":\"echo \\\"hello\\nworld\\\"\"}", canonical);
    }

    @Test
    void nullAndEmptyHandled() {
        ObjectNode node = mapper.createObjectNode();
        node.putNull("x");
        node.putArray("arr");

        String canonical = CanonicalJson.canonicalize(node);
        assertEquals("{\"arr\":[],\"x\":null}", canonical);
    }
}
