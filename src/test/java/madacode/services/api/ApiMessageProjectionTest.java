package madacode.services.api;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiMessageProjectionTest {

    private final ApiMessageProjection projection = new ApiMessageProjection();

    @Test
    void toolResultBlocksMovedToFrontInMergedUserMessage() {
        List<Message> input = List.of(
                Message.assistant(List.of(new ContentBlock.ToolUseBlock(
                        "u1", "bash", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()))),
                Message.controllerEvent("[controller-event] EnterPlanModeTool"),
                Message.user(List.of(
                        new ContentBlock.ToolResultBlock("u1", "ok", true, 100),
                        new ContentBlock.ToolResultBlock("u2", "done", true, 50))));

        List<Message> result = projection.project(input);

        assertEquals(2, result.size());
        Message userMessage = result.get(1);
        assertEquals(MessageRole.USER, userMessage.role());
        List<ContentBlock> blocks = userMessage.contentBlocks();
        assertEquals(3, blocks.size());
        assertEquals(ContentBlock.ToolResultBlock.class, blocks.get(0).getClass());
        assertEquals("u1", ((ContentBlock.ToolResultBlock) blocks.get(0)).toolUseId());
        assertEquals(ContentBlock.ToolResultBlock.class, blocks.get(1).getClass());
        assertEquals("u2", ((ContentBlock.ToolResultBlock) blocks.get(1)).toolUseId());
        assertEquals(ContentBlock.TextBlock.class, blocks.get(2).getClass());
        assertEquals("[controller-event] EnterPlanModeTool",
                ((ContentBlock.TextBlock) blocks.get(2)).text());
    }

    @Test
    void pureToolResultMessageUnchanged() {
        List<Message> input = List.of(Message.user(List.of(
                new ContentBlock.ToolResultBlock("u1", "result", true, 50))));

        List<Message> result = projection.project(input);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).contentBlocks().size());
        assertEquals(ContentBlock.ToolResultBlock.class,
                result.get(0).contentBlocks().getFirst().getClass());
    }

    @Test
    void pureTextMessageUnchanged() {
        List<Message> input = List.of(Message.user("hello world"));

        List<Message> result = projection.project(input);

        assertEquals(1, result.size());
        assertEquals("hello world",
                ((ContentBlock.TextBlock) result.get(0).contentBlocks().getFirst()).text());
    }

    @Test
    void systemMessagesDropped() {
        List<Message> input = List.of(
                Message.system("You are a helpful assistant."),
                Message.user("hi"));

        List<Message> result = projection.project(input);

        assertEquals(1, result.size());
        assertEquals(MessageRole.USER, result.get(0).role());
    }

    @Test
    void adjacentSameRoleMessagesMerged() {
        List<Message> input = List.of(
                Message.user("first"),
                Message.user("second"));

        List<Message> result = projection.project(input);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).contentBlocks().size());
    }
}
