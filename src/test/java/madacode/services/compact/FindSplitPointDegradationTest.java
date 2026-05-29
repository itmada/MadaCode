package madacode.services.compact;

import madacode.core.ContentBlock;
import madacode.core.Message;
import madacode.services.api.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bug 10 regression: findSplitPoint must degrade gracefully when the session
 * has fewer real-user messages than keepRounds, rather than returning 0 and
 * blocking compaction entirely.
 */
class FindSplitPointDegradationTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final ObjectMapper mapper = new ObjectMapper();

    private FullCompactStrategy strategy() {
        return new FullCompactStrategy(
                (msgs, sys, tools, sink, tok) -> new ApiClient.ApiResponse("summary", List.of()),
                estimator, e -> {});
    }

    @Test
    void degradesToFewerRoundsWhenNotEnoughRealUsers() {
        // keepRounds=3 but only 2 real-user messages exist.
        // Before the fix: returns 0 (no split possible).
        // After the fix: degrades to keepRounds=2, finds the first real-user.
        ObjectNode input = mapper.createObjectNode().put("path", "f.txt");
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.user("first real question"),
                Message.assistant(List.of(new ContentBlock.ToolUseBlock("t1", "Read", input))),
                Message.user(List.of(new ContentBlock.ToolResultBlock("t1", "content", true, -1))),
                Message.assistant("handled tool"),
                Message.user("second real question"),
                Message.assistant("answer"));

        int split = strategy().findSplitPoint(messages, 3);

        // Should find index of "first real question" (degraded to keepRounds=2).
        assertEquals(1, split, "should degrade to keepRounds=2 and find index 1");
    }

    @Test
    void degradesToOneRoundWhenOnlySingleRealUser() {
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.user("only question"),
                Message.assistant("answer"));

        int split = strategy().findSplitPoint(messages, 3);

        // With only 1 real-user message, keepRounds degrades to 1 → split at index 1.
        assertEquals(1, split, "should degrade to keepRounds=1");
    }

    @Test
    void returnsZeroWhenNoRealUserMessagesExist() {
        // Edge case: all USER messages are tool_result carriers.
        ObjectNode input = mapper.createObjectNode().put("cmd", "ls");
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.assistant(List.of(new ContentBlock.ToolUseBlock("t1", "bash", input))),
                Message.user(List.of(new ContentBlock.ToolResultBlock("t1", "output", true, -1))),
                Message.assistant("done"));

        int split = strategy().findSplitPoint(messages, 3);

        assertEquals(0, split, "no real-user messages → 0 is correct");
    }

    @Test
    void normalCaseUnchangedWhenEnoughRealUsers() {
        // keepRounds=2, 3 real-user messages → should find the 2nd from the end.
        List<Message> messages = List.of(
                Message.system("Init"),
                Message.user("q1"),
                Message.assistant("a1"),
                Message.user("q2"),
                Message.assistant("a2"),
                Message.user("q3"),
                Message.assistant("a3"));

        int split = strategy().findSplitPoint(messages, 2);

        // 2nd real-user from the end is "q2" at index 3.
        assertEquals(3, split);
    }
}
