package madacode.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InboundTokenCounterTest {

    @Test
    void estimatesTokensFromAccumulatedText() {
        InboundTokenCounter counter = new InboundTokenCounter();

        counter.onTextChunk("abcd");
        counter.onTextChunk("abcdefgh");

        assertEquals(3, counter.currentTokens());
    }

    @Test
    void resetClearsAccumulatedText() {
        InboundTokenCounter counter = new InboundTokenCounter();
        counter.onTextChunk("abcdefgh");

        counter.reset();

        assertEquals(0, counter.currentTokens());
    }
}
