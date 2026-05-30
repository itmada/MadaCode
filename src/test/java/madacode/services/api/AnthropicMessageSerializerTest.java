package madacode.services.api;

import madacode.core.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicMessageSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compactBoundaryUserMessageAppearsInSerializedRequestBody() throws Exception {
        AnthropicMessageSerializer serializer = new AnthropicMessageSerializer(mapper);

        String body = serializer.buildRequestBody(
                "test-model",
                4096,
                List.of(
                        Message.system("Session initialized."),
                        Message.user("[CompactBoundary: 4 messages summarized]\nsummary body"),
                        Message.user("fresh question")),
                "system prompt",
                List.of());

        JsonNode root = mapper.readTree(body);
        assertEquals("system prompt", root.path("system").asText());
        assertEquals(1, root.path("messages").size());
        String serializedMessages = root.path("messages").toString();
        assertTrue(serializedMessages.contains("CompactBoundary"));
        assertTrue(serializedMessages.contains("fresh question"));
    }

    @Test
    void systemMessagesDoNotLeaveAdjacentSameRoleMessages() throws Exception {
        AnthropicMessageSerializer serializer = new AnthropicMessageSerializer(mapper);

        String body = serializer.buildRequestBody(
                "test-model",
                4096,
                List.of(
                        Message.user("tool result or previous user content"),
                        Message.system("(Reached max iterations: 15)"),
                        Message.user("next prompt")),
                "system prompt",
                List.of());

        JsonNode messages = mapper.readTree(body).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        String serialized = messages.get(0).toString();
        assertTrue(serialized.contains("previous user content"));
        assertTrue(serialized.contains("next prompt"));
    }
}
