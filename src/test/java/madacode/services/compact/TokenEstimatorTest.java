package madacode.services.compact;

import madacode.core.ContentBlock;
import madacode.core.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void emptyMessagesIsZero() {
        assertEquals(0, estimator.estimate(List.of()));
    }

    @Test
    void textMessageIncreasesWithLength() {
        Message shortMsg = Message.user("hi");
        Message longMsg = Message.user("a".repeat(1000));
        assertTrue(estimator.estimate(longMsg) > estimator.estimate(shortMsg));
    }

    @Test
    void toolResultContributes() {
        Message withResult = Message.user("small");
        Message withHugeResult = Message.user(List.of(
                new ContentBlock.ToolResultBlock("t1", "x".repeat(5000), true, -1)));
        assertTrue(estimator.estimate(withHugeResult) > estimator.estimate(withResult));
    }

    @Test
    void estimateAcrossMessageList() {
        List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("world"));
        int total = estimator.estimate(messages);
        int sum = estimator.estimate(messages.get(0)) + estimator.estimate(messages.get(1));
        // Roughly sum (within overhead error margin)
        assertTrue(Math.abs(total - sum) < 10);
    }

    @Test
    void textBlockEstimate() {
        ContentBlock small = new ContentBlock.TextBlock("ok");
        ContentBlock big = new ContentBlock.TextBlock("x".repeat(500));
        assertTrue(estimator.estimate(big) > estimator.estimate(small));
    }
}
