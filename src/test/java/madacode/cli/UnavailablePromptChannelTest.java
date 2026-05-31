package madacode.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnavailablePromptChannelTest {

    @Test
    void reportsUnavailable() {
        assertFalse(UnavailablePromptChannel.INSTANCE.isAvailable());
    }

    @Test
    void chooseOneReturnsEmpty() {
        assertTrue(UnavailablePromptChannel.INSTANCE.chooseOne("pick", options("A", "B")).isEmpty());
    }

    @Test
    void chooseManyReturnsEmpty() {
        assertTrue(UnavailablePromptChannel.INSTANCE.chooseMany("pick", options("A", "B")).isEmpty());
    }

    @Test
    void freeTextReturnsEmpty() {
        assertTrue(UnavailablePromptChannel.INSTANCE.freeText("enter text").isEmpty());
    }

    @Test
    void confirmReturnsFalse() {
        assertFalse(UnavailablePromptChannel.INSTANCE.confirm("yes?"));
    }

    @Test
    void singleton() {
        assertSame(UnavailablePromptChannel.INSTANCE, UnavailablePromptChannel.INSTANCE);
    }

    private static List<UserPromptChannel.ChannelOption> options(String... labels) {
        return java.util.Arrays.stream(labels)
                .map(label -> new UserPromptChannel.ChannelOption(label, label))
                .toList();
    }
}
