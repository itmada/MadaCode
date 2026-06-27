package madacode.services.api;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.FinishReason;
import madacode.tool.ToolVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                ToolVisibility.empty());

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
                ToolVisibility.empty());

        JsonNode messages = mapper.readTree(body).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        String serialized = messages.get(0).toString();
        assertTrue(serialized.contains("previous user content"));
        assertTrue(serialized.contains("next prompt"));
    }

    @Test
    void controllerEventsAreSerializedEvenThoughSystemMessagesAreDropped() throws Exception {
        AnthropicMessageSerializer serializer = new AnthropicMessageSerializer(mapper);
        ApiMessageProjection projection = new ApiMessageProjection();

        String body = serializer.buildRequestBody(
                "test-model",
                4096,
                projection.project(List.of(
                        Message.system("Session initialized."),
                        Message.controllerEvent("[controller-event][long-running]\n"
                                + "event: worker_runtime_finished\n"
                                + "summary: interrupted by user"),
                        Message.user("what happened?"))),
                "system prompt",
                ToolVisibility.empty());

        JsonNode messages = mapper.readTree(body).path("messages");
        assertEquals(1, messages.size());
        String serialized = messages.get(0).toString();
        assertTrue(serialized.contains("[controller-event][long-running]"));
        assertTrue(serialized.contains("worker_runtime_finished"));
        assertTrue(serialized.contains("what happened?"));
        assertTrue(!serialized.contains("controller-event barrier"));
    }

    @Test
    void queuedControllerEventSerializesAfterToolResultContent() throws Exception {
        AnthropicMessageSerializer serializer = new AnthropicMessageSerializer(mapper);
        ApiMessageProjection projection = new ApiMessageProjection();
        ObjectNode input = mapper.createObjectNode().put("target_status", "RUNNING");

        String body = serializer.buildRequestBody(
                "test-model",
                4096,
                projection.project(List.of(
                        Message.assistant(List.of(new ContentBlock.ToolUseBlock(
                                "toolu_1", "longrun_state_transition", input))),
                        Message.user(List.of(new ContentBlock.ToolResultBlock(
                                "toolu_1", "State transition applied.", true, -1))),
                        Message.controllerEvent("[controller-event][long-running]\n"
                                + "event: transition_applied"))),
                "system prompt",
                ToolVisibility.empty());

        JsonNode messages = mapper.readTree(body).path("messages");
        assertEquals("assistant", messages.get(0).path("role").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        String serializedUser = messages.get(1).toString();
        assertTrue(serializedUser.indexOf("tool_result") < serializedUser.indexOf("[controller-event][long-running]"),
                serializedUser);
    }

    @Test
    void terminalAssistantMessageSerializesAsPlainAssistantText() throws Exception {
        AnthropicMessageSerializer serializer = new AnthropicMessageSerializer(mapper);

        String body = serializer.buildRequestBody(
                "test-model",
                4096,
                List.of(Message.assistantTerminal("(Cancelled: esc)", FinishReason.CANCELLED)),
                "system prompt",
                ToolVisibility.empty());

        JsonNode message = mapper.readTree(body).path("messages").get(0);
        assertEquals("assistant", message.path("role").asText());
        assertEquals("(Cancelled: esc)", message.path("content").asText());
    }

    @Test
    void promptCachingAddsSystemAndPenultimateMessageBreakpointsOnlyWhenEnabled() throws Exception {
        AnthropicMessageSerializer serializer = new AnthropicMessageSerializer(mapper);

        String cachedBody = serializer.buildRequestBody(
                "test-model",
                4096,
                List.of(
                        Message.user("first"),
                        Message.assistant("second"),
                        Message.user("third")),
                "system prompt",
                ToolVisibility.empty(),
                false,
                true);

        JsonNode cached = mapper.readTree(cachedBody);
        assertEquals("ephemeral", cached.path("system").path("cache_control").path("type").asText());
        assertEquals("system prompt", cached.path("system").path("text").asText());
        assertEquals("ephemeral", cached.path("messages").get(1)
                .path("content").get(0).path("cache_control").path("type").asText());
        assertTrue(cached.path("messages").get(0).path("content").isTextual());
        assertTrue(cached.path("messages").get(2).path("content").isTextual());

        String uncachedBody = serializer.buildRequestBody(
                "test-model",
                4096,
                List.of(Message.user("first"), Message.assistant("second")),
                "system prompt",
                ToolVisibility.empty(),
                false,
                false);
        JsonNode uncached = mapper.readTree(uncachedBody);
        assertEquals("system prompt", uncached.path("system").asText());
        assertTrue(!uncachedBody.contains("cache_control"));
    }

    @Test
    void projectionMergesAdjacentSameRoleMessagesAndDropsSystemMarkers() {
        ApiMessageProjection projection = new ApiMessageProjection();

        List<Message> projected = projection.project(List.of(
                Message.user("first"),
                Message.user("second"),
                Message.system("skip me"),
                Message.controllerEvent("[controller-event][runtime]\nevent: resumed"),
                Message.user("third")));

        assertEquals(1, projected.size());
        assertEquals("firstsecond[controller-event][runtime]\nevent: resumedthird", projected.getFirst().content());
    }
}
