package madacode.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessPromptChannelTest {

    @Test
    void isAvailable_returns_false() {
        assertFalse(HeadlessPromptChannel.INSTANCE.isAvailable());
    }

    @Test
    void chooseOne_returns_empty() {
        assertTrue(HeadlessPromptChannel.INSTANCE.chooseOne("pick", options("A", "B")).isEmpty());
    }

    @Test
    void chooseMany_returns_empty() {
        assertTrue(HeadlessPromptChannel.INSTANCE.chooseMany("pick", options("A", "B")).isEmpty());
    }

    @Test
    void freeText_returns_empty() {
        assertTrue(HeadlessPromptChannel.INSTANCE.freeText("enter text").isEmpty());
    }

    @Test
    void confirm_returns_false() {
        assertFalse(HeadlessPromptChannel.INSTANCE.confirm("yes?"));
    }

    @Test
    void singleton_returns_same_instance() {
        assertSame(HeadlessPromptChannel.INSTANCE, HeadlessPromptChannel.INSTANCE);
    }

    private static List<UserPromptChannel.ChannelOption> options(String... labels) {
        return java.util.Arrays.stream(labels)
                .map(label -> new UserPromptChannel.ChannelOption(label, ""))
                .toList();
    }
}
