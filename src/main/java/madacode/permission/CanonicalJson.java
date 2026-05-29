package madacode.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Deterministic JSON serialisation used by the permission system to decide
 * approval-key equivalence.
 *
 * <p>Two JSON inputs that differ only in object-key order produce the same
 * canonical string. This is the structural fix for Bug 3 — without
 * canonicalisation, the model emitting tool input fields in different orders
 * across calls produces different keys, and the user is re-prompted for
 * approval of what is semantically the same action.
 *
 * <p>This is a pragmatic implementation, not RFC 8785: keys are sorted, but
 * numeric forms are passed through as Jackson rendered them and string
 * escaping follows JSON minimal rules. Sufficient for equality comparison of
 * tool inputs originating from Anthropic Messages API.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        switch (node.getNodeType()) {
            case OBJECT -> writeObject(node, sb);
            case ARRAY -> writeArray(node, sb);
            case STRING -> appendString(sb, node.asText());
            case BOOLEAN -> sb.append(node.asBoolean());
            case NUMBER -> sb.append(node.asText());
            case BINARY, POJO, MISSING -> appendString(sb, node.asText());
        }
    }

    private static void writeObject(JsonNode node, StringBuilder sb) {
        Iterator<String> fields = node.fieldNames();
        List<String> sorted = new ArrayList<>();
        fields.forEachRemaining(sorted::add);
        sorted.sort(null);
        sb.append('{');
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            String key = sorted.get(i);
            appendString(sb, key);
            sb.append(':');
            write(node.get(key), sb);
        }
        sb.append('}');
    }

    private static void writeArray(JsonNode node, StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) sb.append(',');
            write(node.get(i), sb);
        }
        sb.append(']');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
