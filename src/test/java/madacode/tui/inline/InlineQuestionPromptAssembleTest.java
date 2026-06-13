package madacode.tui.inline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InlineQuestionPromptAssembleTest {

    private static final List<InlineQuestionPrompt.Option> OPTS = List.of(
            new InlineQuestionPrompt.Option("Alpha", ""),
            new InlineQuestionPrompt.Option("Beta", ""),
            new InlineQuestionPrompt.Option("Gamma", ""));

    @Test
    void singleSelectChosenOnly() {
        var out = InlineQuestionPrompt.assemble(OPTS, false, new boolean[3], 1, "");
        assertEquals(List.of("Beta"), out);
    }

    @Test
    void singleSelectChosenPlusNoteCombines() {
        var out = InlineQuestionPrompt.assemble(OPTS, false, new boolean[3], 0, "  but RS256  ");
        assertEquals(List.of("Alpha", "but RS256"), out);
    }

    @Test
    void singleSelectNoteOnlyWhenNothingChosen() {
        var out = InlineQuestionPrompt.assemble(OPTS, false, new boolean[3], -1, "just do it");
        assertEquals(List.of("just do it"), out);
    }

    @Test
    void singleSelectEmptyWhenNothing() {
        var out = InlineQuestionPrompt.assemble(OPTS, false, new boolean[3], -1, "   ");
        assertEquals(List.of(), out);
    }

    @Test
    void multiSelectKeepsDisplayOrderAndAppendsNote() {
        boolean[] checked = {true, false, true};
        var out = InlineQuestionPrompt.assemble(OPTS, true, checked, -1, "and offline");
        assertEquals(List.of("Alpha", "Gamma", "and offline"), out);
    }

    @Test
    void multiSelectNoteOnly() {
        var out = InlineQuestionPrompt.assemble(OPTS, true, new boolean[3], -1, "freeform");
        assertEquals(List.of("freeform"), out);
    }

    @Test
    void multiSelectEmpty() {
        var out = InlineQuestionPrompt.assemble(OPTS, true, new boolean[3], -1, null);
        assertEquals(List.of(), out);
    }
}
