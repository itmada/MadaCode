package madacode.tui.inline;

import madacode.tui.widget.ChoicePrompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChoiceFilterTest {

    @Test
    void emptyFilterReturnsAllIndexes() {
        assertEquals(List.of(0, 1, 2), InlineChoicePrompt.filteredIndexes(model(), ""));
    }

    @Test
    void filterIsCaseInsensitive() {
        assertEquals(List.of(1), InlineChoicePrompt.filteredIndexes(model(), "BETA"));
    }

    @Test
    void filterMatchesSecondaryText() {
        assertEquals(List.of(2), InlineChoicePrompt.filteredIndexes(model(), "third"));
    }

    @Test
    void filterReturnsEmptyListWhenNoOptionMatches() {
        assertEquals(List.of(), InlineChoicePrompt.filteredIndexes(model(), "missing"));
    }

    private static ChoicePrompt.Model<String> model() {
        return new ChoicePrompt.Model<>(
                "Pick",
                "",
                List.of(
                        new ChoicePrompt.Option<>("a", "Alpha", "first", ""),
                        new ChoicePrompt.Option<>("b", "beta", "second", ""),
                        new ChoicePrompt.Option<>("c", "Gamma", "third item", "")),
                "",
                0);
    }
}
