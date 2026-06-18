package madacode.eval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** One scripted user turn and the condition under which it should be sent. */
public record ConversationTurn(String text, Trigger trigger) {

    public ConversationTurn {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("conversation turn text must not be blank");
        }
        text = text.strip();
        trigger = Objects.requireNonNullElse(trigger, Trigger.ALWAYS);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConversationTurn fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("conversation turn must not be null");
        }
        if (node.isTextual()) {
            return new ConversationTurn(node.textValue(), Trigger.ALWAYS);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("conversation turn must be a string or object");
        }
        Set<String> allowed = Set.of("text", "trigger");
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        "unknown conversation turn field '" + field + "'");
            }
        }
        String text = node.path("text").isTextual() ? node.path("text").textValue() : null;
        Trigger trigger = node.has("trigger")
                ? Trigger.parse(node.path("trigger").asText())
                : Trigger.ALWAYS;
        return new ConversationTurn(text, trigger);
    }

    public enum Trigger {
        ALWAYS,
        WHEN_AGENT_ASKS;

        static Trigger parse(String value) {
            if (value == null) {
                return ALWAYS;
            }
            String normalized = value.strip()
                    .replace('-', '_')
                    .replaceAll("([a-z])([A-Z])", "$1_$2")
                    .toUpperCase(java.util.Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown conversation trigger '" + value + "'", e);
            }
        }
    }
}
