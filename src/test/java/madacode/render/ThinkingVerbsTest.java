package madacode.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ThinkingVerbsTest {

    @Test
    void pickUsesSevenSecondBuckets() {
        assertEquals("Pondering", ThinkingVerbs.pick(0));
        assertEquals("Thinking", ThinkingVerbs.pick(7000));
        assertEquals("Cogitating", ThinkingVerbs.pick(14_000));
    }

    @Test
    void pickWrapsForLongElapsedValues() {
        assertNotNull(ThinkingVerbs.pick(Long.MAX_VALUE));
    }
}
